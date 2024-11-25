
package org.jruby.ext.ffi;

import org.jruby.Ruby;
import org.jruby.RubyClass;
import org.jruby.RubyModule;
import org.jruby.RubyString;
import org.jruby.anno.JRubyClass;
import org.jruby.anno.JRubyMethod;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;

import static org.jruby.api.Convert.castAsClass;
import static org.jruby.api.Create.newString;
import static org.jruby.api.Error.typeError;


@JRubyClass(name="FFI::StructByValue", parent="FFI::Type")
public final class StructByValue extends Type {
    private final StructLayout structLayout;
    private final RubyClass structClass;

    public static RubyClass createStructByValueClass(Ruby runtime, RubyModule ffiModule) {
        final RubyClass Type = ffiModule.getClass("Type");
        RubyClass sbvClass = ffiModule.defineClassUnder("StructByValue", Type, ObjectAllocator.NOT_ALLOCATABLE_ALLOCATOR);
        sbvClass.defineAnnotatedMethods(StructByValue.class);
        sbvClass.defineAnnotatedConstants(StructByValue.class);

        Type.setConstant("Struct", sbvClass);

        return sbvClass;
    }

    @JRubyMethod(name = "new", meta = true)
    public static final IRubyObject newStructByValue(ThreadContext context, IRubyObject klass, IRubyObject structClass1) {
        RubyClass structClass = castAsClass(context, structClass1);

        if (!structClass.isKindOfModule(context.runtime.getFFI().structClass)) {
            throw typeError(context, structClass, "subclass of FFI::Struct");
        }

        return new StructByValue(context, (RubyClass) klass, structClass, Struct.getStructLayout(context, structClass));
    }

    private StructByValue(ThreadContext context, RubyClass klass, RubyClass structClass, StructLayout structLayout) {
        super(context.runtime, klass, NativeType.STRUCT, structLayout.size, structLayout.alignment);
        this.structClass = structClass;
        this.structLayout = structLayout;
    }

    StructByValue(Ruby runtime, RubyClass structClass, StructLayout structLayout) {
        super(runtime, runtime.getModule("FFI").getClass("Type").getClass("Struct"),
                NativeType.STRUCT, structLayout.size, structLayout.alignment);
        this.structClass = structClass;
        this.structLayout = structLayout;
    }

    @JRubyMethod(name = "to_s")
    public final IRubyObject to_s(ThreadContext context) {
        return newString(context, String.format("#<FFI::StructByValue:%s>", structClass.getName()));
    }

    @JRubyMethod(name = "layout")
    public final IRubyObject layout(ThreadContext context) {
        return structLayout;
    }

    @JRubyMethod(name = "struct_class")
    public final IRubyObject struct_class(ThreadContext context) {
        return structClass;
    }

    public final StructLayout getStructLayout() {
        return structLayout;
    }

    public final RubyClass getStructClass() {
        return structClass;
    }
}
