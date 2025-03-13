exclude :test_change_struct, "work in progress"
exclude :test_class_ivar, "needs investigation"
exclude :test_class_nonascii, "needs investigation"
exclude :test_continuation, "not supported"
exclude :test_hash_compared_by_identity, "work in progress"
exclude :test_hash_default_compared_by_identity, "work in progress"
exclude :test_hash_subclass_extend, "work in progress, may only happen in JIT"
exclude :test_inconsistent_struct, "needs investigation"
exclude :test_marshal_complex, "needs investigation"
exclude :test_marshal_dump_adding_instance_variable, "work in progress"
exclude :test_marshal_dump_ivar, "needs investigation"
exclude :test_marshal_dump_removing_instance_variable, "work in progress"
exclude :test_marshal_load_extended_class_crash, "needs investigation #4303"
exclude :test_marshal_load_ivar, "needs investigation"
exclude :test_marshal_load_r_prepare_reference_crash, "needs investigation #4303"
exclude :test_marshal_load_should_not_taint_classes, "needs investigation"
exclude :test_marshal_nameerror, "work in progress"
exclude :test_marshal_rational, "needs investigation"
exclude :test_marshal_with_ruby2_keywords_hash, "work in progress"
exclude :test_modify_array_during_dump, "needs investigation"
exclude :test_module_ivar, "needs investigation"
exclude :test_no_internal_ids, "debug info for frozen strings is visible in JRuby"
exclude :test_object_prepend, "needs investigation"
exclude :test_singleton, "needs investigation"
exclude :test_struct_invalid_members, "needs investigation"
exclude :test_symlink_in_ivar, "seems to be trying to deserialize a symbol with ivars, which we don't support"
exclude :test_time_subclass, "needs investigation"
exclude :test_unloadable_data, "we do not represent Time as T_DATA internally, so this test does not apply"
exclude :test_unloadable_userdef, "multibyte class names don't marshal properly (#3688)"
exclude :test_unloadable_usrmarshal, "multibyte class names don't marshal properly (#3688)"
exclude :test_userdef_encoding, "needs investigation"
