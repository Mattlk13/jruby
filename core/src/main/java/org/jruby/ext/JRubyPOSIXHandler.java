/*
 * Copyright (c) 2015 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.ext;

import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import jnr.constants.platform.Errno;
import org.jruby.Ruby;
import org.jruby.RubyHash;
import org.jruby.common.IRubyWarnings.ID;
import jnr.posix.POSIXHandler;

import org.jruby.util.cli.Options;

import static org.jruby.api.Access.objectClass;

public class JRubyPOSIXHandler implements POSIXHandler {
    private final Ruby runtime;
    private final boolean isVerbose;
    
    public JRubyPOSIXHandler(Ruby runtime) {
        this.runtime = runtime;

        boolean verbose = false;
        try {
            verbose = Options.NATIVE_VERBOSE.load();
        } catch (SecurityException e) {
        }
        this.isVerbose = verbose;
    }

    public void error(Errno error, String extraData) {
        throw runtime.newErrnoFromInt(error.intValue(), extraData);
    }
    
    public void error(Errno error, String methodName, String extraData) {
        throw runtime.newErrnoFromInt(error.intValue(), methodName, extraData);
    }

    public void unimplementedError(String method) {
        throw runtime.newNotImplementedError(method + " unsupported or native support failed to load; see https://github.com/jruby/jruby/wiki/Native-Libraries");
    }

    public void warn(WARNING_ID id, String message, Object... data) {
        ID ourID;
        if (id == WARNING_ID.DUMMY_VALUE_USED) {
            ourID = ID.DUMMY_VALUE_USED;
        } else {
            ourID = ID.MISCELLANEOUS;
        }
        runtime.getWarnings().warn(ourID, message);
    }
    
    public boolean isVerbose() {
        return isVerbose;
    }
    
    public File getCurrentWorkingDirectory() {
        return new File(runtime.getCurrentDirectory());
    }

    @SuppressWarnings("unchecked")
    public String[] getEnv() {
        var context = runtime.getCurrentContext();
        RubyHash hash = (RubyHash) objectClass(context).getConstant(context, "ENV");

        String[] env = new String[hash.size()];
        if (env.length == 0) return env;

        StringBuilder str = new StringBuilder(); int i=0;

        for (Iterator<Entry<Object, Object>> iter = hash.directEntrySet().iterator(); iter.hasNext(); i++) {
            Map.Entry<Object, Object> entry = iter.next();
            str.setLength(0);
            str.append(entry.getKey()).append('=').append(entry.getValue());
            env[i] = str.toString();
        }

        return env;
    }
    
    public PrintStream getErrorStream() {
         return runtime.getErrorStream();
    }

    public InputStream getInputStream() {
         return runtime.getInputStream();
    }
    
    public int getPID() {
        return runtime.hashCode();
    }

    public PrintStream getOutputStream() {
         return runtime.getOutputStream();
    }
}
