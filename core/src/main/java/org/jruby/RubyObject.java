/*
 ***** BEGIN LICENSE BLOCK *****
 * Version: EPL 2.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Eclipse Public
 * License Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/epl-v20.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2001 Chad Fowler <chadfowler@chadfowler.com>
 * Copyright (C) 2001 Alan Moore <alan_moore@gmx.net>
 * Copyright (C) 2001-2002 Benoit Cerrina <b.cerrina@wanadoo.fr>
 * Copyright (C) 2001-2004 Jan Arne Petersen <jpetersen@uni-bonn.de>
 * Copyright (C) 2002-2004 Anders Bengtsson <ndrsbngtssn@yahoo.se>
 * Copyright (C) 2004-2006 Thomas E Enebo <enebo@acm.org>
 * Copyright (C) 2004-2005 Charles O Nutter <headius@headius.com>
 * Copyright (C) 2004 Stefan Matthias Aust <sma@3plus4.de>
 * Copyright (C) 2006 Ola Bini <ola.bini@ki.se>
 * Copyright (C) 2006 Miguel Covarrubias <mlcovarrubias@gmail.com>
 * Copyright (C) 2007 MenTaLguY <mental@rydia.net>
 * Copyright (C) 2007 William N Dortch <bill.dortch@gmail.com>
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the EPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the EPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/

package org.jruby;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Set;
import java.util.function.BiConsumer;

import org.jruby.anno.JRubyClass;
import org.jruby.runtime.Block;
import org.jruby.runtime.ClassIndex;
import org.jruby.runtime.JavaSites.ObjectSites;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.runtime.marshal.DataType;

import static org.jruby.api.Convert.asBoolean;
import static org.jruby.api.Convert.toInt;
import static org.jruby.api.Error.argumentError;
import static org.jruby.api.Error.typeError;
import static org.jruby.runtime.Helpers.invokedynamic;
import static org.jruby.runtime.Helpers.throwException;
import static org.jruby.runtime.invokedynamic.MethodNames.EQL;
import static org.jruby.runtime.invokedynamic.MethodNames.OP_EQUAL;
import static org.jruby.runtime.invokedynamic.MethodNames.HASH;

import org.jruby.util.cli.Options;

/**
 * RubyObject represents the implementation of the Object class in Ruby. As such,
 * it defines very few methods of its own, inheriting most from the included
 * Kernel module.
 *
 * Methods that are implemented here, such as "initialize" should be implemented
 * with care; reification of Ruby classes into Java classes can produce
 * conflicting method names in rare cases. See JRUBY-5906 for an example.
 */
@JRubyClass(name="Object", include="Kernel")
public class RubyObject extends RubyBasicObject {
    // Equivalent of T_DATA
    public static class Data extends RubyObject implements DataType {
        public Data(Ruby runtime, RubyClass metaClass, Object data) {
            super(runtime, metaClass);
            dataWrapStruct(data);
        }

        public Data(RubyClass metaClass, Object data) {
            super(metaClass);
            dataWrapStruct(data);
        }
    }

    /**
     * Standard path for object creation. Objects are entered into ObjectSpace
     * only if ObjectSpace is enabled.
     * @param runtime the runtime
     * @param metaClass the metaclass
     */
    public RubyObject(Ruby runtime, RubyClass metaClass) {
        super(runtime, metaClass);
    }

    /**
     * Path for objects that don't enter objectspace.
     * @param metaClass the metaclass
     */
    public RubyObject(RubyClass metaClass) {
        super(metaClass);
    }

    @Deprecated
    protected RubyObject(Ruby runtime, RubyClass metaClass, boolean useObjectSpace, boolean canBeTainted) {
        super(runtime, metaClass, useObjectSpace, canBeTainted);
    }

    /**
     * Path for objects who want to decide whether they don't want to be in
     * ObjectSpace even when it is on. (notably used by objects being
     * considered immediate, they'll always pass false here)
     * @param runtime the runtime
     * @param metaClass the metaclass
     * @param useObjectSpace to enable object space
     */
    protected RubyObject(Ruby runtime, RubyClass metaClass, boolean useObjectSpace) {
        super(runtime, metaClass, useObjectSpace);
    }

    /**
     * Reify the class and allocate an instance.
     *
     * @param runtime the current runtime
     * @param klass the target Ruby class
     * @return a new instance of the now-reified class
     */
    private static IRubyObject reifyAndAllocate(Ruby runtime, RubyClass klass) {
        klass.reifyWithAncestors();
        return klass.allocate(runtime.getCurrentContext());
    }

    /**
     * Will create the Ruby class Object in the runtime
     * specified. This method needs to take the actual class as an
     * argument because of the Object class' central part in runtime
     * initialization.
     * @param Object the object claass
     */
    public static void finishObjectClass(RubyClass Object) {
        Object.reifiedClass(RubyObject.class).classIndex(ClassIndex.OBJECT);
    }

    /**
     * Default allocator instance for all Ruby objects. The only
     * reason to not use this allocator is if you actually need to
     * have all instances of something be a subclass of RubyObject.
     *
     * @see org.jruby.runtime.ObjectAllocator
     */
    public static final ObjectAllocator OBJECT_ALLOCATOR = RubyObject::new;

    /**
     * Allocator that inspects all methods for instance variables and chooses
     * a concrete class to construct based on that. This allows using
     * specialized subclasses to hold instance variables in fields rather than
     * always holding them in an array.
     */
    public static final ObjectAllocator IVAR_INSPECTING_OBJECT_ALLOCATOR = new ObjectAllocator() {
        @Override
        public IRubyObject allocate(Ruby runtime, RubyClass klass) {
            ObjectAllocator allocator = klass.getAllocator();

            if (allocator == this) {
                // eagerly gather variables outside of sync
                Set<String> foundVariables = klass.discoverInstanceVariables();

                synchronized (klass.getRealClass()) {
                    // check again before reifying
                    allocator = klass.getAllocator();

                    if (allocator == this) {
                        // proceed, we are the first one to get here

                        if (Options.DUMP_INSTANCE_VARS.load()) {
                            System.err.println(klass + ";" + foundVariables);
                        }

                        allocator = runtime.getObjectSpecializer().specializeForVariables(klass, foundVariables);

                        // invalidate metaclass so new allocator is picked up for specialized .new
                        klass.metaClass.invalidateCacheDescendants(runtime.getCurrentContext());
                    }
                }
            }

            return allocator.allocate(runtime, klass);
        }
    };

    public static final ObjectAllocator REIFYING_OBJECT_ALLOCATOR = RubyObject::reifyAndAllocate;

    /**
     * Will make sure that this object is added to the current object
     * space.
     *
     * @see org.jruby.runtime.ObjectSpace
     */
    public void attachToObjectSpace() {
        getRuntime().getObjectSpace().add(this);
    }

    /**
     * This is overridden in the other concrete Java builtins to provide a fast way
     * to determine what type they are.
     *
     * Will generally return a value from org.jruby.runtime.ClassIndex
     *
     * @see org.jruby.runtime.ClassIndex
     */
    @Override
    public ClassIndex getNativeClassIndex() {
        return ClassIndex.OBJECT;
    }

    /**
     * Simple helper to print any objects.
     * @deprecated no longer used - uses Java's System.out
     * @param obj to puts
     */
    @Deprecated
    public static void puts(Object obj) {
        System.out.println(obj.toString());
    }

    /**
     * This override does not do a "checked" dispatch.
     *
     * @see RubyBasicObject#equals(Object)
     */
    @Override
    public boolean equals(Object other) {
        return other == this ||
                other instanceof IRubyObject &&
                invokedynamic(metaClass.runtime.getCurrentContext(), this, OP_EQUAL, (IRubyObject) other).isTrue();
    }

    /**
     * The default toString method is just a wrapper that calls the
     * Ruby "to_s" method.  This will raise if it is not actually a Ruby String.
     *
     * @param context thread context this is executing on.
     * @return the string.
     */
    public RubyString toRubyString(ThreadContext context) {
        return sites(context).to_s.call(context, this, this).convertToString();
    }

    /**
     * The default toString method is just a wrapper that calls the
     * Ruby "to_s" method.
     * @return string representation
     */
    @Override
    public String toString() {
        return toRubyString(metaClass.runtime.getCurrentContext()).getUnicodeValue();
    }

    /**
     * Call the Ruby initialize method with the supplied arguments and block.
     * @param args args
     * @param block block
     */
    public final void callInit(IRubyObject[] args, Block block) {
        ThreadContext context = metaClass.runtime.getCurrentContext();
        metaClass.getBaseCallSite(RubyClass.CS_IDX_INITIALIZE).call(context, this, this, args, block);
    }

    /**
     * Call the Ruby initialize method with the supplied arguments and block.
     * @param block block
     */
    public final void callInit(Block block) {
        ThreadContext context = metaClass.runtime.getCurrentContext();
        metaClass.getBaseCallSite(RubyClass.CS_IDX_INITIALIZE).call(context, this, this, block);
    }

    /**
     * Call the Ruby initialize method with the supplied arguments and block.
     * @param arg0 arg0
     * @param block block
     */
    public final void callInit(IRubyObject arg0, Block block) {
        ThreadContext context = metaClass.runtime.getCurrentContext();
        metaClass.getBaseCallSite(RubyClass.CS_IDX_INITIALIZE).call(context, this, this, arg0, block);
    }

    /**
     * Call the Ruby initialize method with the supplied arguments and block.
     * @param arg0 arg0
     * @param arg1 arg1
     * @param block block
     */
    public final void callInit(IRubyObject arg0, IRubyObject arg1, Block block) {
        ThreadContext context = metaClass.runtime.getCurrentContext();
        metaClass.getBaseCallSite(RubyClass.CS_IDX_INITIALIZE).call(context, this, this, arg0, arg1, block);
    }

    /*
     * Call the Ruby initialize method with the supplied arguments and block.
     */
    public final void callInit(IRubyObject arg0, IRubyObject arg1, IRubyObject arg2, Block block) {
        ThreadContext context = metaClass.runtime.getCurrentContext();
        metaClass.getBaseCallSite(RubyClass.CS_IDX_INITIALIZE).call(context, this, this, arg0, arg1, arg2, block);
    }

    public final void callInit(ThreadContext context, IRubyObject[] args, Block block) {
        metaClass.getBaseCallSite(RubyClass.CS_IDX_INITIALIZE).call(context, this, this, args, block);
    }

    public final void callInit(ThreadContext context, Block block) {
        metaClass.getBaseCallSite(RubyClass.CS_IDX_INITIALIZE).call(context, this, this, block);
    }

    public final void callInit(ThreadContext context, IRubyObject arg0, Block block) {
        metaClass.getBaseCallSite(RubyClass.CS_IDX_INITIALIZE).call(context, this, this, arg0, block);
    }

    public final void callInit(ThreadContext context, IRubyObject arg0, IRubyObject arg1, Block block) {
        metaClass.getBaseCallSite(RubyClass.CS_IDX_INITIALIZE).call(context, this, this, arg0, arg1, block);
    }

    public final void callInit(ThreadContext context, IRubyObject arg0, IRubyObject arg1, IRubyObject arg2, Block block) {
        metaClass.getBaseCallSite(RubyClass.CS_IDX_INITIALIZE).call(context, this, this, arg0, arg1, arg2, block);
    }

    /*
     * Tries to convert this object to the specified Ruby type, using
     * a specific conversion method.
     */
    @Deprecated
    public final IRubyObject convertToType(RubyClass target, int convertMethodIndex) {
        throw new RuntimeException("Not supported; use the String versions");
    }

    /* specific_eval
     *
     * Evaluates the block or string inside of the context of this
     * object, using the supplied arguments. If a block is given, this
     * will be yielded in the specific context of this object. If no
     * block is given then a String-like object needs to be the first
     * argument, and this string will be evaluated. Second and third
     * arguments in the args-array is optional, but can contain the
     * filename and line of the string under evaluation.
     */
    @Deprecated
    public IRubyObject specificEval(ThreadContext context, RubyModule mod, IRubyObject[] args, Block block, EvalType evalType) {
        if (block.isGiven()) {
            if (args.length > 0) throw argumentError(context, args.length, 0);

            return yieldUnder(context, mod, block, evalType);
        }

        if (args.length == 0) throw argumentError(context, "block not supplied");
        if (args.length > 3) {
            String lastFuncName = context.getFrameName();
            throw argumentError(context, "wrong number of arguments: " + lastFuncName + "(src) or " + lastFuncName + "{..}");
        }

        // We just want the TypeError if the argument doesn't convert to a String (JRUBY-386)
        RubyString evalStr = args[0] instanceof RubyString str ? str : args[0].convertToString();

        String file;
        int line;
        if (args.length > 1) {
            file = args[1].convertToString().asJavaString();
            line = args.length > 2 ? toInt(context, args[2]) - 1 : 0;
        } else {
            file = "(eval at " + context.getFileAndLine() + ")";
            line = 0;
        }

        return evalUnder(context, mod, evalStr, file, line, evalType);
    }

    // Methods of the Object class (rb_obj_*):

    /** rb_equal
     *
     * The Ruby "===" method is used by default in case/when
     * statements. The Object implementation first checks Java identity
     * equality and then calls the "==" method too.
     */
    @Override
    public IRubyObject op_eqq(ThreadContext context, IRubyObject other) {
        return asBoolean(context, equalInternal(context, this, other));
    }

    /**
     * Helper method for checking equality, first using Java identity
     * equality, and then calling the "==" method.
     * @param context the thread context
     * @param a first comparator
     * @param b second comparator
     * @return true if equal
     */
    public static boolean equalInternal(final ThreadContext context, final IRubyObject a, final IRubyObject b) {
        if (a == b) return true;
        if (a instanceof RubySymbol) return false;

        return fastNumEqualInternal(context, a, b);
    }

    private static boolean fastNumEqualInternal(final ThreadContext context, final IRubyObject a, final IRubyObject b) {
        if (a instanceof RubyFixnum) {
            if (b instanceof RubyFixnum) {
                if (!context.sites.Fixnum.op_eqq.isBuiltin(a)) {
                    return context.sites.Fixnum.op_eqq.call(context, a, a, b).isTrue();
                }
                return ((RubyFixnum) a).fastEqual((RubyFixnum) b);
            }
        } else if (a instanceof RubyFloat) {
            if (b instanceof RubyFloat) {
                if (!context.sites.Float.op_equal.isBuiltin(a)) {
                    return context.sites.Float.op_equal.call(context, a, a, b).isTrue();
                }
                return ((RubyFloat) a).fastEqual((RubyFloat) b);
            }
        }
        return invokedynamic(context, a, OP_EQUAL, b).isTrue();
    }

    /**
     * Helper method for checking equality, first using Java identity
     * equality, and then calling the "eql?" method.
     * @param context the thread context
     * @param a first comparator
     * @param b second comparator
     * @return true if eql
     */
    protected static boolean eqlInternal(final ThreadContext context, final IRubyObject a, final IRubyObject b){
        if (a == b) return true;
        if (a instanceof RubySymbol) return false;
        if (a instanceof RubyNumeric) {
            if (a.getClass() != b.getClass()) return false;
            return fastNumEqualInternal(context, a, b);
        }
        return invokedynamic(context, a, EQL, b).isTrue();
    }

    /**
     * This override does not do "checked" dispatch since Object usually has #hash defined.
     *
     * @see RubyBasicObject#hashCode()
     * @return the hash code
     */
    @Override
    public int hashCode() {
        var context = getRuntime().getCurrentContext();
        IRubyObject hashValue = invokedynamic(context, this, HASH);

        return hashValue instanceof RubyFixnum fixnum ?
                (int) fixnum.getValue() :
                nonFixnumHashCode(context, hashValue);
    }

    /** rb_inspect
     *
     * The internal helper that ensures a RubyString instance is returned
     * so dangerous casting can be omitted
     * Preferred over callMethod(context, "inspect")
     * @param context the thread context
     * @param object the object to inspect
     * @return the string
     */
    public static RubyString inspect(ThreadContext context, IRubyObject object) {
        return (RubyString)rbInspect(context, object);
    }

    // MRI: rb_obj_dig
    public static IRubyObject dig(ThreadContext context, IRubyObject obj, IRubyObject[] args, int idx) {
        if ( obj.isNil() ) return context.nil;

        ObjectSites sites = sites(context);

        for (; idx < args.length; idx++) {
            if ( obj.isNil() ) break;
            IRubyObject arg = args[idx];
            if (isArrayDig(obj, sites)) {
                obj = ((RubyArray) obj).dig(context, arg);
            } else if (isHashDig(obj, sites)) {
                obj =  ((RubyHash) obj).dig(context, arg);
            } else if (isStructDig(obj, sites)) {
                obj =  ((RubyStruct) obj).dig(context, arg);
            } else if (sites.respond_to_dig.respondsTo(context, obj, obj, true) ) {
                final int len = args.length - idx;
                switch (len) {
                    case 1:
                        return sites.dig_misc.call(context, obj, obj, args[idx]);
                    case 2:
                        return sites.dig_misc.call(context, obj, obj, args[idx], args[idx + 1]);
                    case 3:
                        return sites.dig_misc.call(context, obj, obj, args[idx], args[idx + 1], args[idx + 2]);
                    default:
                        IRubyObject[] rest = new IRubyObject[len];
                        System.arraycopy(args, idx, rest, 0, len);
                        return sites.dig_misc.call(context, obj, obj, rest);

                }
            } else {
                throw typeError(context, "", obj, " does not have #dig method");
            }
        }

        return obj;
    }

    public static IRubyObject dig1(ThreadContext context, IRubyObject obj, IRubyObject arg1) {
        if ( obj.isNil() ) return context.nil;

        ObjectSites sites = sites(context);

        if (isArrayDig(obj, sites)) return ((RubyArray) obj).dig(context, arg1);
        if (isHashDig(obj, sites)) return ((RubyHash) obj).dig(context, arg1);
        if (isStructDig(obj, sites)) return ((RubyStruct) obj).dig(context, arg1);

        if (!sites.respond_to_dig.respondsTo(context, obj, obj, true)) throw typeError(context, "", obj," does not have #dig method");
        return sites.dig_misc.call(context, obj, obj, arg1);
    }

    public static IRubyObject dig2(ThreadContext context, IRubyObject obj, IRubyObject arg1, IRubyObject arg2) {
        if ( obj.isNil() ) return context.nil;

        ObjectSites sites = sites(context);

        if (isArrayDig(obj, sites)) return ((RubyArray) obj).dig(context, arg1, arg2);
        if (isHashDig(obj, sites)) return ((RubyHash) obj).dig(context, arg1, arg2);
        if (isStructDig(obj, sites)) return ((RubyStruct) obj).dig(context, arg1, arg2);

        if (!sites.respond_to_dig.respondsTo(context, obj, obj, true)) throw typeError(context, "", obj," does not have #dig method");
        return sites.dig_misc.call(context, obj, obj, arg1, arg2);
    }

    private static boolean isStructDig(IRubyObject obj, ObjectSites sites) {
        return obj instanceof RubyStruct && sites.dig_struct.isBuiltin(obj.getMetaClass());
    }

    private static boolean isHashDig(IRubyObject obj, ObjectSites sites) {
        return obj instanceof RubyHash && sites.dig_hash.isBuiltin(obj.getMetaClass());
    }

    private static boolean isArrayDig(IRubyObject obj, ObjectSites sites) {
        return obj instanceof RubyArray && sites.dig_array.isBuiltin(obj.getMetaClass());
    }

    /**
     * Tries to support Java serialization of Ruby objects. This is
     * still experimental and might not work.
     */
    // NOTE: Serialization is primarily supported for testing purposes, and there is no general
    // guarantee that serialization will work correctly. Specifically, instance variables pointing
    // at symbols, threads, modules, classes, and other unserializable types are not detected.
    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();

        // write out ivar count followed by name/value pairs
        ObjectOutputter outputter = new ObjectOutputter(out);
        forEachInstanceVariable(outputter);
        outputter.writeCount();
        forEachInstanceVariable(outputter);
    }

    private static class ObjectOutputter implements BiConsumer<String, IRubyObject> {
        final ObjectOutputStream out;
        int count = 0;
        boolean counting = true;

        ObjectOutputter(ObjectOutputStream out) {
            this.out = out;
        }

        public void accept(String name, IRubyObject value) {
            if (counting) {
                count++;
            } else {
                try {
                    out.writeObject(name);
                    out.writeObject(value);
                } catch (IOException ioe) {
                    throwException(ioe);
                }
            }
        }

        public void writeCount() {
            try {
                out.writeInt(count);
                counting = false;
            } catch (IOException ioe) {
                throwException(ioe);
            }
        }
    }

    /**
     * Tries to support Java unserialization of Ruby objects. This is
     * still experimental and might not work.
     */
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        // rest in ivar count followed by name/value pairs
        int ivarCount = in.readInt();
        for (int i = 0; i < ivarCount; i++) {
            setInstanceVariable((String)in.readObject(), (IRubyObject)in.readObject());
        }
    }

    private static ObjectSites sites(ThreadContext context) {
        return context.sites.Object;
    }

}
