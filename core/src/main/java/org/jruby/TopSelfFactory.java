/***** BEGIN LICENSE BLOCK *****
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
 * Copyright (C) 2002 Jan Arne Petersen <jpetersen@uni-bonn.de>
 * Copyright (C) 2002-2004 Anders Bengtsson <ndrsbngtssn@yahoo.se>
 * Copyright (C) 2004 Thomas E Enebo <enebo@acm.org>
 * Copyright (C) 2004 Stefan Matthias Aust <sma@3plus4.de>
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

import org.jruby.internal.runtime.methods.JavaMethod;
import org.jruby.runtime.Arity;
import org.jruby.runtime.Block;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.Visibility;
import org.jruby.runtime.builtin.IRubyObject;

import static org.jruby.api.Access.objectClass;
import static org.jruby.api.Create.newString;

public final class TopSelfFactory {

    /**
     * Constructor for TopSelfFactory.
     */
    private TopSelfFactory() {
        super();
    }

    @Deprecated(since = "10.0")
    public static IRubyObject createTopSelf(final Ruby runtime) {
        var Object = objectClass(runtime.getCurrentContext());
        var topSelf = new RubyObject(runtime, Object);
        return finishTopSelf(runtime.getCurrentContext(), topSelf, Object, false);
    }
    
    public static IRubyObject finishTopSelf(ThreadContext context, IRubyObject topSelf, RubyClass Object, final boolean wrapper) {
        final RubyClass singletonClass = topSelf.singletonClass(context);

        singletonClass.addMethod(context, "to_s", new JavaMethod.JavaMethodZero(singletonClass, Visibility.PUBLIC, "to_s") {
            @Override
            public IRubyObject call(ThreadContext context, IRubyObject self, RubyModule clazz, String name) {
                return newString(context, "main");
            }
        });
        singletonClass.defineAlias(context, "inspect", "to_s");
        
        // The following three methods must be defined fast, since they expect to modify the current frame
        // (i.e. they expect no frame will be allocated for them). JRUBY-1185.
        singletonClass.addMethod(context, "include", new JavaMethod.JavaMethodN(singletonClass, Visibility.PRIVATE, "include") {
            @Override
            public IRubyObject call(ThreadContext context, IRubyObject self, RubyModule clazz, String name, IRubyObject[] args) {
                return Object.include(context, args);
            }
        });
        
        singletonClass.addMethod(context, "public", new JavaMethod.JavaMethodN(singletonClass, Visibility.PRIVATE, "public") {
            @Override
            public IRubyObject call(ThreadContext context, IRubyObject self, RubyModule clazz, String name, IRubyObject[] args) {
                return Object._public(context, args);
            }
        });
        
        singletonClass.addMethod(context, "private", new JavaMethod.JavaMethodN(singletonClass, Visibility.PRIVATE, "private") {
            @Override
            public IRubyObject call(ThreadContext context, IRubyObject self, RubyModule clazz, String name, IRubyObject[] args) {
                return Object._private(context, args);
            }
        });

        singletonClass.addMethod(context, "ruby2_keywords", new JavaMethod.JavaMethodN(singletonClass, Visibility.PRIVATE, "private") {
            @Override
            public IRubyObject call(ThreadContext context, IRubyObject self, RubyModule clazz, String name, IRubyObject[] args) {
                return Object.ruby2_keywords(context, args);
            }
        });

        final RubyClass klass = wrapper ? singletonClass : Object;
        singletonClass.addMethod(context, "define_method", new JavaMethod.JavaMethodOneOrTwoBlock(singletonClass, Visibility.PRIVATE, "define_method") {
            @Override
            public IRubyObject call(ThreadContext context, IRubyObject self, RubyModule clazz, String name, IRubyObject arg0, Block block) {
                if (klass == singletonClass) warnWrapper(context);
                return klass.defineMethodFromBlock(context, arg0, block, Visibility.PUBLIC);
            }

            @Override
            public IRubyObject call(ThreadContext context, IRubyObject self, RubyModule clazz, String name, IRubyObject arg0, IRubyObject arg1, Block block) {
                if (klass == singletonClass) warnWrapper(context);
                return klass.defineMethodFromCallable(context, arg0, arg1, Visibility.PUBLIC);
            }
        });

        singletonClass.addMethod(context, "using", new JavaMethod.JavaMethodN(singletonClass, Visibility.PRIVATE, "using") {
            @Override
            public IRubyObject call(ThreadContext context, IRubyObject self, RubyModule clazz, String name, IRubyObject[] args) {
                Arity.checkArgumentCount(context, args, 1, 1);
                RubyModule cref = context.getCurrentStaticScope().getOverlayModuleForWrite(context);
                // unclear what the equivalent check would be for us
//                rb_control_frame_t * prev_cfp = previous_frame(GET_THREAD());
//
//                if (CREF_NEXT(cref) || (prev_cfp && prev_cfp->me)) {
//                    rb_raise(rb_eRuntimeError,
//                            "main.using is permitted only at toplevel");
//                }
                RubyModule.usingModule(context, cref, args[0]);
                return self;
            }
        });
        
        return topSelf;
    }

    private static void warnWrapper(ThreadContext context) {
        context.runtime.getWarnings().warning("main.define_method in the wrapped load is effective only in wrapper module");
    }
}
