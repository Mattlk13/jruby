/*
 **** BEGIN LICENSE BLOCK *****
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
 * Copyright (C) 2001 Alan Moore <alan_moore@gmx.net>
 * Copyright (C) 2001-2002 Benoit Cerrina <b.cerrina@wanadoo.fr>
 * Copyright (C) 2001-2004 Jan Arne Petersen <jpetersen@uni-bonn.de>
 * Copyright (C) 2002-2004 Anders Bengtsson <ndrsbngtssn@yahoo.se>
 * Copyright (C) 2004-2005 Thomas E Enebo <enebo@acm.org>
 * Copyright (C) 2004 Stefan Matthias Aust <sma@3plus4.de>
 * Copyright (C) 2005 David Corbin <dcorbin@users.sourceforge.net>
 * Copyright (C) 2006 Nick Sieger <nicksieger@gmail.com>
 * Copyright (C) 2006 Miguel Covarrubias <mlcovarrubias@gmail.com>
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

import org.jcodings.Encoding;
import org.jcodings.specific.ASCIIEncoding;
import org.jcodings.specific.USASCIIEncoding;
import org.joni.Matcher;
import org.joni.NameEntry;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Region;
import org.joni.Syntax;
import org.joni.WarnCallback;
import org.joni.exception.JOniException;
import org.joni.exception.TimeoutException;
import org.jruby.anno.JRubyClass;
import org.jruby.anno.JRubyMethod;
import org.jruby.api.Convert;
import org.jruby.exceptions.RaiseException;
import org.jruby.parser.ReOptions;
import org.jruby.runtime.Block;
import org.jruby.runtime.ClassIndex;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.Visibility;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.runtime.encoding.EncodingCapable;
import org.jruby.runtime.encoding.MarshalEncoding;
import org.jruby.runtime.marshal.MarshalDumper;
import org.jruby.util.ByteList;
import org.jruby.util.KCode;
import org.jruby.util.RegexpOptions;
import org.jruby.util.RegexpSupport;
import org.jruby.util.StringSupport;
import org.jruby.util.TypeConverter;
import org.jruby.util.cli.Options;
import org.jruby.util.collections.WeakValuedMap;
import org.jruby.util.io.EncodingUtils;
import org.jruby.util.io.RubyOutputStream;

import java.util.Iterator;

import static org.jruby.anno.FrameField.BACKREF;
import static org.jruby.anno.FrameField.LASTLINE;
import static org.jruby.api.Access.encodingService;
import static org.jruby.api.Access.instanceConfig;
import static org.jruby.api.Convert.asBoolean;
import static org.jruby.api.Convert.asFixnum;
import static org.jruby.api.Convert.asFloat;
import static org.jruby.api.Convert.asSymbol;
import static org.jruby.api.Convert.toInt;
import static org.jruby.api.Create.dupString;
import static org.jruby.api.Create.newEmptyArray;
import static org.jruby.api.Create.newHash;
import static org.jruby.api.Create.newSharedString;
import static org.jruby.api.Create.newString;
import static org.jruby.api.Define.defineClass;
import static org.jruby.api.Error.argumentError;
import static org.jruby.api.Error.indexError;
import static org.jruby.api.Error.runtimeError;
import static org.jruby.api.Error.typeError;
import static org.jruby.api.Warn.warn;
import static org.jruby.api.Warn.warning;
import static org.jruby.runtime.ThreadContext.resetCallInfo;
import static org.jruby.util.RubyStringBuilder.str;
import static org.jruby.util.StringSupport.CR_7BIT;
import static org.jruby.util.StringSupport.EMPTY_STRING_ARRAY;

@JRubyClass(name="Regexp")
public class RubyRegexp extends RubyObject implements ReOptions, EncodingCapable, MarshalEncoding {
    Regex pattern;
    private ByteList str = ByteList.EMPTY_BYTELIST;
    private RegexpOptions options;

    private IRubyObject timeout;

    private static final ThreadLocal<IRubyObject[]> TL_HOLDER = ThreadLocal.withInitial(() -> new IRubyObject[1]);

    public static final int ARG_ENCODING_FIXED     =   ReOptions.RE_FIXED;
    public static final int ARG_ENCODING_NONE      =   ReOptions.RE_NONE;

    public void setLiteral() {
        setFrozen(true);
        options.setLiteral(true);
    }

    public void clearLiteral() {
        options.setLiteral(false);
    }

    public boolean isLiteral() {
        return options.isLiteral();
    }

    public boolean isKCodeDefault() {
        return options.isKcodeDefault();
    }

    public void setEncodingNone() {
        options.setEncodingNone(true);
    }

    public void clearEncodingNone() {
        options.setEncodingNone(false);
    }

    public boolean isEncodingNone() {
        return options.isEncodingNone();
    }

    public KCode getKCode() {
        return options.getKCode();
    }

    @Override
    public Encoding getEncoding() {
        return pattern.getEncoding();
    }

    @Override
    public void setEncoding(Encoding encoding) {
        // FIXME: Which encoding should be changed here?
        // FIXME: transcode?
    }

    @Override
    public boolean shouldMarshalEncoding() {
        return getEncoding() != ASCIIEncoding.INSTANCE;
    }

    @Override
    public Encoding getMarshalEncoding() {
        return getEncoding();
    }

    static final WeakValuedMap<ByteList, Regex> patternCache = new WeakValuedMap();
    static final WeakValuedMap<ByteList, Regex> quotedPatternCache = new WeakValuedMap();
    static final WeakValuedMap<ByteList, Regex> preprocessedPatternCache = new WeakValuedMap();

    private static Regex makeRegexp(Ruby runtime, ByteList bytes, RegexpOptions options, Encoding enc) {
        try {
            int p = bytes.getBegin();
            return new Regex(bytes.getUnsafeBytes(), p, p + bytes.getRealSize(), options.toJoniOptions(), enc, Syntax.DEFAULT, runtime.getRegexpWarnings());
        } catch (Exception e) {
            String err = e.getMessage();
            RegexpSupport.raiseRegexpError(runtime, bytes, enc, options, err);
            return null; // not reached
        }
    }

    public static Regex getRegexpFromCache(Ruby runtime, ByteList bytes, Encoding enc, RegexpOptions options) {
        Regex regex = patternCache.get(bytes);
        if (regex != null && regex.getEncoding() == enc && regex.getOptions() == options.toJoniOptions()) return regex;
        regex = makeRegexp(runtime, bytes, options, enc);
        regex.setUserObject(bytes);
        patternCache.put(bytes, regex);
        return regex;
    }

    static Regex getQuotedRegexpFromCache(ThreadContext context, RubyString str, RegexpOptions options) {
        final ByteList bytes = str.getByteList();
        Regex regex = quotedPatternCache.get(bytes);
        Encoding enc = str.isAsciiOnly() ? USASCIIEncoding.INSTANCE : bytes.getEncoding();
        if (regex != null && regex.getEncoding() == enc && regex.getOptions() == options.toJoniOptions()) return regex;
        final ByteList quoted = quote(str);
        regex = makeRegexp(context.runtime, quoted, options, quoted.getEncoding());
        regex.setUserObject(quoted);
        quotedPatternCache.put(bytes, regex);
        return regex;
    }

    private static Regex getPreprocessedRegexpFromCache(ThreadContext context, ByteList bytes, Encoding enc,
                                                        RegexpOptions options, RegexpSupport.ErrorMode mode) {
        Regex regex = preprocessedPatternCache.get(bytes);
        if (regex != null && regex.getEncoding() == enc && regex.getOptions() == options.toJoniOptions()) return regex;
        ByteList preprocessed = RegexpSupport.preprocess(context.runtime, bytes, enc, new Encoding[]{null}, RegexpSupport.ErrorMode.RAISE);
        regex = makeRegexp(context.runtime, preprocessed, options, enc);
        regex.setUserObject(preprocessed);
        preprocessedPatternCache.put(bytes, regex);
        return regex;
    }

    public static RubyClass createRegexpClass(ThreadContext context, RubyClass Object) {
        RubyClass Regexp = defineClass(context, "Regexp", Object, RubyRegexp::new).
                reifiedClass(RubyRegexp.class).
                kindOf(new RubyModule.JavaClassKindOf(RubyRegexp.class)).
                classIndex(ClassIndex.REGEXP).
                defineConstant(context, "IGNORECASE", asFixnum(context, RE_OPTION_IGNORECASE)).
                defineConstant(context, "EXTENDED", asFixnum(context, RE_OPTION_EXTENDED)).
                defineConstant(context, "MULTILINE", asFixnum(context, RE_OPTION_MULTILINE)).
                defineConstant(context, "FIXEDENCODING", asFixnum(context, RE_FIXED)).
                defineConstant(context, "NOENCODING", asFixnum(context, RE_NONE)).
                defineMethods(context, RubyRegexp.class).
                tap(c -> c.singletonClass(context).defineAlias(context, "compile", "new"));

        context.runtime.setRubyTimeout(context.nil);

        return Regexp;
    }

    public static int matcherSearch(ThreadContext context, Matcher matcher, int start, int range, int option) {
        if (!instanceConfig(context).isInterruptibleRegexps()) return matcher.search(start, range, option);

        try {
            return context.getThread().executeRegexp(context, matcher, start, range, option, Matcher::searchInterruptible);
        } catch (TimeoutException e) {
            throw context.runtime.newRaiseException(context.runtime.getRegexpTimeoutError(), "regexp match timeout");
        } catch (InterruptedException e) {
            throw context.runtime.newInterruptedRegexpError("Regexp Interrupted");
        }
    }

    public static int matcherMatch(ThreadContext context, Matcher matcher, int start, int range, int option) {
        if (!instanceConfig(context).isInterruptibleRegexps()) return matcher.match(start, range, option);

        try {
            return context.getThread().executeRegexp(context, matcher, start, range, option, Matcher::matchInterruptible);
        } catch (TimeoutException e) {
            throw context.runtime.newRaiseException(context.runtime.getRegexpTimeoutError(), "regexp match timeout");
        } catch (InterruptedException e) {
            throw context.runtime.newInterruptedRegexpError("Regexp Interrupted");
        }
    }

    @Deprecated // not-used
    public static int matcherSearch(Ruby runtime, Matcher matcher, int start, int range, int option) {
        return matcherSearch(runtime.getCurrentContext(), matcher, start, range, option);
    }

    @Deprecated // not-used
    public static int matcherMatch(Ruby runtime, Matcher matcher, int start, int range, int option) {
        return matcherMatch(runtime.getCurrentContext(), matcher, start, range, option);
    }

    @Override
    public ClassIndex getNativeClassIndex() {
        return ClassIndex.REGEXP;
    }

    /** used by allocator
     */
    private RubyRegexp(Ruby runtime, RubyClass klass) {
        super(runtime, klass);
        this.options = new RegexpOptions();
    }

    /** default constructor
     */
    RubyRegexp(Ruby runtime) {
        super(runtime, runtime.getRegexp());
        this.options = new RegexpOptions();
    }

    public RubyRegexp(Ruby runtime, Regex pattern, ByteList str, RegexpOptions options) {
        super(runtime, runtime.getRegexp());
        this.pattern = pattern;
        this.str = str;
        this.options = options;
    }

    private RubyRegexp(Ruby runtime, ByteList str) {
        this(runtime);
        assert str != null;
        this.str = str;
        this.pattern = getRegexpFromCache(runtime, str, str.getEncoding(), RegexpOptions.NULL_OPTIONS);
    }

    private RubyRegexp(Ruby runtime, ByteList str, RegexpOptions options) {
        this(runtime);
        assert str != null;

        regexpInitialize(str, str.getEncoding(), options, null);
    }

    // used only by the compiler/interpreter (will set the literal flag)
    public static RubyRegexp newRegexp(Ruby runtime, String pattern, RegexpOptions options) {
        return newRegexp(runtime, ByteList.create(pattern), options);
    }

    // used only by the compiler/interpreter (will set the literal flag)
    public static RubyRegexp newRegexp(Ruby runtime, ByteList pattern, int options) {
        return newRegexp(runtime, pattern, RegexpOptions.fromEmbeddedOptions(options));
    }

    // used only by the compiler/interpreter (will set the literal flag)
    public static RubyRegexp newRegexp(Ruby runtime, ByteList pattern, RegexpOptions options) {
        try {
            return new RubyRegexp(runtime, pattern, options.clone());
        } catch (RaiseException re) {
            throw runtime.newSyntaxError(re.getMessage());
        }
    }

    /**
     * throws RaiseException on error so parser can pick this up and give proper line and line number
     * error as opposed to any non-literal regexp creation which may raise a syntax error but will not
     * have this extra source info in the error message
     */
    public static RubyRegexp newRegexpParser(Ruby runtime, ByteList pattern, RegexpOptions options) {
        return new RubyRegexp(runtime, pattern, options.clone());
    }

    // used only by the compiler/interpreter (will set the literal flag)
    public static RubyRegexp newDRegexp(Ruby runtime, RubyString pattern, RegexpOptions options) {
        try {
            return new RubyRegexp(runtime, pattern.getByteList(), options.clone());
        } catch (RaiseException re) {
            throw runtime.newRegexpError(re.getMessage());
        }
    }

    // used only by the compiler/interpreter (will set the literal flag)
    public static RubyRegexp newDRegexp(Ruby runtime, RubyString pattern, int joniOptions) {
        try {
            RegexpOptions options = RegexpOptions.fromJoniOptions(joniOptions);
            return new RubyRegexp(runtime, pattern.getByteList(), options);
        } catch (RaiseException re) {
            throw runtime.newRegexpError(re.getMessage());
        }
    }

    public static RubyRegexp newRegexp(Ruby runtime, ByteList pattern) {
        return new RubyRegexp(runtime, pattern);
    }

    static RubyRegexp newRegexp(Ruby runtime, ByteList str, Regex pattern) {
        RubyRegexp regexp = new RubyRegexp(runtime);
        assert str != null;
        regexp.str = str;
        regexp.options = RegexpOptions.fromJoniOptions(pattern.getOptions());
        regexp.pattern = pattern;
        return regexp;
    }

    // internal usage (Complex/Rational)
    static RubyRegexp newDummyRegexp(Ruby runtime, Regex regex) {
        RubyRegexp regexp = new RubyRegexp(runtime);
        regexp.pattern = regex;
        regexp.str = ByteList.EMPTY_BYTELIST;
        regexp.options.setFixed(true);
        return regexp;
    }

    // MRI: rb_reg_new_str
    public static RubyRegexp newRegexpFromStr(Ruby runtime, RubyString s, int options) {
        var context = runtime.getCurrentContext();
        RubyRegexp re = (RubyRegexp)runtime.getRegexp().allocate(context);
        re.regexpInitializeString(context, s, RegexpOptions.fromJoniOptions(options), null);
        return re;
    }

    @Deprecated(since = "10.0")
    public final RegexpOptions getOptions() {
        return getOptions(getCurrentContext());
    }

    /** rb_reg_options
     */
    public final RegexpOptions getOptions(ThreadContext context) {
        check(context);
        return options;
    }

    @Deprecated(since = "10.0")
    public final Regex getPattern() {
        return getPattern(getCurrentContext());
    }

    public final Regex getPattern(ThreadContext context) {
        check(context);
        return pattern;
    }

    Encoding checkEncoding(ThreadContext context, RubyString other) {
        Encoding enc = other.isCompatibleWith(this);
        if (enc == null) encodingMatchError(context, pattern, other.getEncoding());
        return enc;
    }

    private static void encodingMatchError(ThreadContext context, Regex pattern, Encoding strEnc) {
        throw context.runtime.newEncodingCompatibilityError("incompatible encoding regexp match (" +
                pattern.getEncoding() + " regexp with " + strEnc + " string)");
    }

    private Encoding prepareEncoding(ThreadContext context, RubyString str, boolean warn) {
        Encoding enc = str.getEncoding();
        int cr = str.scanForCodeRange();
        if (cr == StringSupport.CR_BROKEN) throw argumentError(context, "invalid byte sequence in " + enc);

        check(context);
        Encoding patternEnc = pattern.getEncoding();
        if (patternEnc == enc) {
        } else if (cr == StringSupport.CR_7BIT && patternEnc == USASCIIEncoding.INSTANCE) {
            enc = patternEnc;
        } else if (!enc.isAsciiCompatible()) {
            encodingMatchError(context, pattern, enc);
        } else if (options.isFixed()) {
            if (enc != patternEnc &&
               (!patternEnc.isAsciiCompatible() ||
               cr != StringSupport.CR_7BIT)) encodingMatchError(context, pattern, enc);
            enc = patternEnc;
        }
        if (warn && isEncodingNone() && enc != ASCIIEncoding.INSTANCE && cr != StringSupport.CR_7BIT) {
            warn(context, "historical binary regexp match /.../n against " + enc + " string");
        }
        return enc;
    }

    @Deprecated(since = "10.0")
    public final Regex preparePattern(RubyString str) {
        return preparePattern(getCurrentContext(), str);
    }

    public final Regex preparePattern(ThreadContext context, RubyString str) {
        // checkEncoding does `check();` no need to here
        Encoding enc = prepareEncoding(context, str, true);
        if (enc == pattern.getEncoding()) return pattern;
        return getPreprocessedRegexpFromCache(context, this.str, enc, options, RegexpSupport.ErrorMode.PREPROCESS);
    }


    /**
     * Preprocess the given string for use in regexp, raising errors for encoding
     * incompatibilities that arise.
     *
     * This version does not produce a new, unescaped version of the bytelist,
     * and simply does the string-walking portion of the logic.
     *
     * @param context the current context
     * @param str string to preprocess
     * @param enc string's encoding
     * @param fixedEnc new encoding after fixing
     * @param mode mode of errors
     */
    private static void preprocessLight(ThreadContext context, ByteList str, Encoding enc, Encoding[]fixedEnc, RegexpSupport.ErrorMode mode) {
        fixedEnc[0] = enc.isAsciiCompatible() ? null : enc;
        boolean hasProperty = RegexpSupport.unescapeNonAscii(context.runtime, null, str.getUnsafeBytes(), str.getBegin(), str.getBegin() + str.getRealSize(), enc, fixedEnc, str, mode);
        if (hasProperty && fixedEnc[0] == null) fixedEnc[0] = enc;
    }

    public static void preprocessCheck(Ruby runtime, ByteList bytes) {
        RegexpSupport.preprocess(runtime, bytes, bytes.getEncoding(), new Encoding[]{null}, RegexpSupport.ErrorMode.RAISE);
    }

    @Deprecated // not used
    public static RubyString preprocessDRegexp(Ruby runtime, RubyString[] strings, int embeddedOptions) {
        return preprocessDRegexp(runtime, strings, RegexpOptions.fromEmbeddedOptions(embeddedOptions));
    }

    // rb_reg_preprocess_dregexp
    public static RubyString preprocessDRegexp(Ruby runtime, IRubyObject[] strings, RegexpOptions options) {
        return preprocessDRegexp(runtime.getCurrentContext(), options, strings);
    }

    // rb_reg_preprocess_dregexp
    public static RubyString preprocessDRegexp(ThreadContext context, RegexpOptions options, IRubyObject... args) {
        RubyString string = null;
        Encoding regexpEnc = null;

        for (IRubyObject arg : args) {
            RubyString str = arg.convertToString();
            regexpEnc = processDRegexpElement(context, options, regexpEnc, context.encodingHolder(), str);
            string = string == null ? (RubyString) str.dup() : string.append(str);
        }

        if (regexpEnc != null) string.setEncoding(regexpEnc);

        return string;
    }

    public static RubyString preprocessDRegexp(ThreadContext context, RegexpOptions options, IRubyObject arg0) {
        return processElementIntoResult(context, null, arg0, options, null, context.encodingHolder());
    }

    @Deprecated // not used
    public static RubyString preprocessDRegexp(Ruby runtime, IRubyObject arg0, RegexpOptions options) {
        var context = runtime.getCurrentContext();
        return processElementIntoResult(context, null, arg0, options, null, context.encodingHolder());
    }

    public static RubyString preprocessDRegexp(ThreadContext context, RegexpOptions options, IRubyObject arg0, IRubyObject arg1) {
        return processElementIntoResult(context, null, arg0, arg1, options, null, context.encodingHolder());
    }

    @Deprecated
    public static RubyString preprocessDRegexp(Ruby runtime, IRubyObject arg0, IRubyObject arg1, RegexpOptions options) {
        var context = runtime.getCurrentContext();
        return processElementIntoResult(context, null, arg0, arg1, options, null, context.encodingHolder());
    }

    public static RubyString preprocessDRegexp(ThreadContext context, RegexpOptions options, IRubyObject arg0, IRubyObject arg1, IRubyObject arg2) {
        return processElementIntoResult(context, null, arg0, arg1, arg2, options, null, context.encodingHolder());
    }

    @Deprecated
    public static RubyString preprocessDRegexp(Ruby runtime, IRubyObject arg0, IRubyObject arg1, IRubyObject arg2, RegexpOptions options) {
        var context = runtime.getCurrentContext();
        return processElementIntoResult(context, null, arg0, arg1, arg2, options, null, context.encodingHolder());
    }

    @Deprecated
    public static RubyString preprocessDRegexp(Ruby runtime, IRubyObject arg0, IRubyObject arg1, IRubyObject arg2, IRubyObject arg3, RegexpOptions options) {
        var context = runtime.getCurrentContext();
        return processElementIntoResult(context, null, arg0, arg1, arg2, arg3, options, null, context.encodingHolder());
    }

    @Deprecated
    public static RubyString preprocessDRegexp(Ruby runtime, IRubyObject arg0, IRubyObject arg1, IRubyObject arg2, IRubyObject arg3, IRubyObject arg4, RegexpOptions options) {
        var context = runtime.getCurrentContext();
        return processElementIntoResult(context, null, arg0, arg1, arg2, arg3, arg4, options, null, context.encodingHolder());
    }

    private static RubyString processElementIntoResult(
            ThreadContext context,
            RubyString result,
            IRubyObject arg0,
            IRubyObject arg1,
            IRubyObject arg2,
            IRubyObject arg3,
            IRubyObject arg4,
            RegexpOptions options,
            Encoding regexpEnc,
            Encoding[] fixedEnc) {
        RubyString str = arg0.convertToString();
        regexpEnc = processDRegexpElement(context, options, regexpEnc, fixedEnc, str);
        return processElementIntoResult(context, result == null ? dupString(context, str) : result.append(str), arg1, arg2, arg3, arg4, options, regexpEnc, fixedEnc);
    }

    private static RubyString processElementIntoResult(
            ThreadContext context,
            RubyString result,
            IRubyObject arg0,
            IRubyObject arg1,
            IRubyObject arg2,
            IRubyObject arg3,
            RegexpOptions options,
            Encoding regexpEnc,
            Encoding[] fixedEnc) {
        RubyString str = arg0.convertToString();
        regexpEnc = processDRegexpElement(context, options, regexpEnc, fixedEnc, str);
        return processElementIntoResult(context, result == null ? dupString(context, str) : result.append(str), arg1, arg2, arg3, options, regexpEnc, fixedEnc);
    }

    private static RubyString processElementIntoResult(
            ThreadContext context,
            RubyString result,
            IRubyObject arg0,
            IRubyObject arg1,
            IRubyObject arg2,
            RegexpOptions options,
            Encoding regexpEnc,
            Encoding[] fixedEnc) {
        RubyString str = arg0.convertToString();
        regexpEnc = processDRegexpElement(context, options, regexpEnc, fixedEnc, str);
        return processElementIntoResult(context, result == null ? dupString(context, str) : result.append(str), arg1, arg2, options, regexpEnc, fixedEnc);
    }

    private static RubyString processElementIntoResult(
            ThreadContext context,
            RubyString result,
            IRubyObject arg0,
            IRubyObject arg1,
            RegexpOptions options,
            Encoding regexpEnc,
            Encoding[] fixedEnc) {
        RubyString str = arg0.convertToString();
        regexpEnc = processDRegexpElement(context, options, regexpEnc, fixedEnc, str);
        return processElementIntoResult(context, result == null ? dupString(context, str) : result.append(str), arg1, options, regexpEnc, fixedEnc);
    }

    private static RubyString processElementIntoResult(
            ThreadContext context,
            RubyString result,
            IRubyObject arg0,
            RegexpOptions options,
            Encoding regexpEnc,
            Encoding[] fixedEnc) {
        RubyString str = arg0.convertToString();
        regexpEnc = processDRegexpElement(context, options, regexpEnc, fixedEnc, str);
        result = result == null ? dupString(context, str) : result.append(str);
        if (regexpEnc != null) result.setEncoding(regexpEnc);
        return result;
    }

    private static Encoding processDRegexpElement(ThreadContext context, RegexpOptions options, Encoding regexpEnc, Encoding[] fixedEnc, RubyString str) {
        Encoding strEnc = str.getEncoding();

        if (options.isEncodingNone() && strEnc != ASCIIEncoding.INSTANCE) {
            if (str.scanForCodeRange() != StringSupport.CR_7BIT) {
                throw context.runtime.newRegexpError("/.../n has a non escaped non ASCII character in non ASCII-8BIT script");
            }
            strEnc = ASCIIEncoding.INSTANCE;
        }

        // This used to call preprocess, but the resulting bytelist was not
        // used. Since the preprocessing error-checking can be done without
        // creating a new bytelist, I added a "light" path.
        RubyRegexp.preprocessLight(context, str.getByteList(), strEnc, fixedEnc, RegexpSupport.ErrorMode.PREPROCESS);

        if (fixedEnc[0] != null) {
            if (regexpEnc != null && regexpEnc != fixedEnc[0]) {
                throw context.runtime.newRegexpError("encoding mismatch in dynamic regexp: " +
                        new String(regexpEnc.getName()) + " and " + new String(fixedEnc[0].getName())
                );
            }
            regexpEnc = fixedEnc[0];
        }
        return regexpEnc;
    }

    private void check(ThreadContext context) {
        if (pattern == null) throw typeError(context, "uninitialized Regexp");
    }

    @JRubyMethod(meta = true)
    public static IRubyObject try_convert(ThreadContext context, IRubyObject recv, IRubyObject args) {
        return TypeConverter.convertToTypeWithCheck(args, context.runtime.getRegexp(), "to_regexp");
    }

    /** rb_reg_s_quote
     *
     */
    @JRubyMethod(name = {"quote", "escape"}, meta = true)
    public static RubyString quote(ThreadContext context, IRubyObject recv, IRubyObject arg) {
        return newSharedString(context, quote(operandCheck(context, arg)));
    }

    static ByteList quote(final RubyString str) {
        final ByteList bytes = str.getByteList();
        final ByteList qBytes = quote(bytes, str.isAsciiOnly());
        if (qBytes == bytes) str.setByteListShared();
        return qBytes;
    }

    /** rb_reg_quote
     *
     */
    private static final int QUOTED_V = 11;
    static ByteList quote(ByteList bs, boolean asciiOnly) {
        int p = bs.getBegin();
        int end = p + bs.getRealSize();
        byte[] bytes = bs.getUnsafeBytes();
        Encoding enc = bs.getEncoding();

        metaFound: do {
            while (p < end) {
                final int c;
                final int cl;
                if (enc.isAsciiCompatible()) {
                    cl = 1;
                    c = bytes[p] & 0xff;
                } else {
                    cl = StringSupport.preciseLength(enc, bytes, p, end);
                    if (cl < 0) {
                        p += StringSupport.length(enc, bytes, p, end);
                        continue;
                    }
                    c = enc.mbcToCode(bytes, p, end);
                }

                if (!Encoding.isAscii(c)) {
                    p += StringSupport.length(enc, bytes, p, end);
                    continue;
                }

                switch (c) {
                case '[': case ']': case '{': case '}':
                case '(': case ')': case '|': case '-':
                case '*': case '.': case '\\':
                case '?': case '+': case '^': case '$':
                case ' ': case '#':
                case '\t': case '\f': case QUOTED_V: case '\n': case '\r':
                    break metaFound;
                }
                p += cl;
            }
            if (asciiOnly) {
                ByteList tmp = bs.shallowDup();
                tmp.setEncoding(USASCIIEncoding.INSTANCE);
                return tmp;
            }
            return bs;
        } while (false);

        ByteList result = new ByteList(end * 2);
        result.setEncoding(asciiOnly ? USASCIIEncoding.INSTANCE : bs.getEncoding());
        byte[] obytes = result.getUnsafeBytes();
        int op = p - bs.getBegin();
        System.arraycopy(bytes, bs.getBegin(), obytes, 0, op);

        while (p < end) {
            final int c;
            final int cl;
            if (enc.isAsciiCompatible()) {
                cl = 1;
                c = bytes[p] & 0xff;
            } else {
                cl = StringSupport.preciseLength(enc, bytes, p, end);
                c = enc.mbcToCode(bytes, p, end);
            }

            if (!Encoding.isAscii(c)) {
                int n = StringSupport.length(enc, bytes, p, end);
                while (n-- > 0) obytes[op++] = bytes[p++];
                continue;
            }
            p += cl;
            switch (c) {
            case '[': case ']': case '{': case '}':
            case '(': case ')': case '|': case '-':
            case '*': case '.': case '\\':
            case '?': case '+': case '^': case '$':
            case '#':
                op += enc.codeToMbc('\\', obytes, op);
                break;
            case ' ':
                op += enc.codeToMbc('\\', obytes, op);
                op += enc.codeToMbc(' ', obytes, op);
                continue;
            case '\t':
                op += enc.codeToMbc('\\', obytes, op);
                op += enc.codeToMbc('t', obytes, op);
                continue;
            case '\n':
                op += enc.codeToMbc('\\', obytes, op);
                op += enc.codeToMbc('n', obytes, op);
                continue;
            case '\r':
                op += enc.codeToMbc('\\', obytes, op);
                op += enc.codeToMbc('r', obytes, op);
                continue;
            case '\f':
                op += enc.codeToMbc('\\', obytes, op);
                op += enc.codeToMbc('f', obytes, op);
                continue;
            case QUOTED_V:
                op += enc.codeToMbc('\\', obytes, op);
                op += enc.codeToMbc('v', obytes, op);
                continue;
            }
            op += enc.codeToMbc(c, obytes, op);
        }

        result.setRealSize(op);
        return result;
    }

    /** rb_reg_s_last_match / match_getter
    *
    */
    @JRubyMethod(name = "last_match", meta = true, reads = BACKREF)
    public static IRubyObject last_match_s(ThreadContext context, IRubyObject recv) {
        return context.getBackRef();
    }

    /** rb_reg_s_last_match
    *
    */
    @JRubyMethod(name = "last_match", meta = true, reads = BACKREF)
    public static IRubyObject last_match_s(ThreadContext context, IRubyObject recv, IRubyObject nth) {
        return context.getBackRef() instanceof RubyMatchData match ?
                nth_match(context, match.backrefNumber(context, nth), match) :
                context.nil;
    }

    /** rb_reg_s_union
    *
    */
    @JRubyMethod(name = "union", rest = true, meta = true)
    public static IRubyObject union(ThreadContext context, IRubyObject recv, IRubyObject[] args) {
        IRubyObject obj;
        if (args.length == 1 && !(obj = args[0].checkArrayType()).isNil()) {
            var ary = (RubyArray<?>) obj;
            IRubyObject[] tmp = new IRubyObject[ary.size()];
            ary.copyInto(context, tmp, 0);
            args = tmp;
        }

        Ruby runtime = context.runtime;
        if (args.length == 0) {
            return runtime.getRegexp().newInstance(context, newString(context, "(?!)"), Block.NULL_BLOCK);
        } else if (args.length == 1) {
            IRubyObject re = TypeConverter.convertToTypeWithCheck(args[0], runtime.getRegexp(), "to_regexp");
            return !re.isNil() ? re : newRegexpFromStr(runtime, quote(context, recv, args[0]), 0);
        } else {
            boolean hasAsciiOnly = false;
            final RubyString source = runtime.newString();
            Encoding hasAsciiCompatFixed = null;
            Encoding hasAsciiIncompat = null;

            byte [] verticalVarBytes = new byte[]{'|'};
            for (int i = 0; i < args.length; i++) {
                IRubyObject e = args[i];
                if (i > 0) source.catAscii(verticalVarBytes, 0, 1);
                IRubyObject v = TypeConverter.convertToTypeWithCheck(e, runtime.getRegexp(), "to_regexp");
                final Encoding enc; final ByteList re;
                if (v != context.nil) {
                    RubyRegexp regex = (RubyRegexp) v;
                    enc = regex.getEncoding();
                    if (!enc.isAsciiCompatible()) {
                        if (hasAsciiIncompat == null) { // First regexp of union sets kcode.
                            hasAsciiIncompat = enc;
                        } else if (hasAsciiIncompat != enc) { // n kcode doesn't match first one
                            throw argumentError(context, "incompatible encodings: " + hasAsciiIncompat + " and " + enc);
                        }
                    } else if (regex.getOptions(context).isFixed()) {
                        if (hasAsciiCompatFixed == null) { // First regexp of union sets kcode.
                            hasAsciiCompatFixed = enc;
                        } else if (hasAsciiCompatFixed != enc) { // n kcode doesn't match first one
                            throw argumentError(context, "incompatible encodings: " + hasAsciiCompatFixed + " and " + enc);
                        }
                    } else {
                        hasAsciiOnly = true;
                    }
                    re = ((RubyString) regex.to_s(context)).getByteList();
                } else {
                    RubyString str = e.convertToString();
                    enc = str.getEncoding();

                    if (!enc.isAsciiCompatible()) {
                        if (hasAsciiIncompat == null) { // First regexp of union sets kcode.
                            hasAsciiIncompat = enc;
                        } else if (hasAsciiIncompat != enc) { // n kcode doesn't match first one
                            throw argumentError(context, "incompatible encodings: " + hasAsciiIncompat + " and " + enc);
                        }
                    } else if (str.isAsciiOnly()) {
                        hasAsciiOnly = true;
                    } else {
                        if (hasAsciiCompatFixed == null) { // First regexp of union sets kcode.
                            hasAsciiCompatFixed = enc;
                        } else if (hasAsciiCompatFixed != enc) { // n kcode doesn't match first one
                            throw argumentError(context, "incompatible encodings: " + hasAsciiCompatFixed + " and " + enc);
                        }
                    }
                    re = quote(str);
                }

                if (hasAsciiIncompat != null) {
                    if (hasAsciiOnly) {
                        throw argumentError(context, "ASCII incompatible encoding: " + hasAsciiIncompat);
                    }
                    if (hasAsciiCompatFixed != null) {
                        throw argumentError(context, "incompatible encodings: " + hasAsciiIncompat + " and " + hasAsciiCompatFixed);
                    }
                }

                // set encoding for first append
                if (i == 0) source.setEncoding(enc);
                source.cat(re);
            }
            if (hasAsciiIncompat != null) {
                source.setEncoding(hasAsciiIncompat);
            } else if (hasAsciiCompatFixed != null) {
                source.setEncoding(hasAsciiCompatFixed);
            } else {
                source.setEncoding(ASCIIEncoding.INSTANCE);
            }
            return runtime.getRegexp().newInstance(context, source, Block.NULL_BLOCK);
        }
    }

    /** rb_reg_init_copy
     */
    @JRubyMethod(visibility = Visibility.PRIVATE)
    public IRubyObject initialize_copy(ThreadContext context, IRubyObject re) {
        if (this == re) return this;
        checkFrozen();

        if (getMetaClass().getRealClass() != re.getMetaClass().getRealClass()) {
            throw typeError(context, "wrong argument type");
        }

        RubyRegexp regexp = (RubyRegexp)re;
        regexp.check(context);

        return regexpInitialize(regexp.str, regexp.str.getEncoding(), regexp.getOptions(context), regexp.timeout);
    }

    private static int objectAsJoniOptions(ThreadContext context, IRubyObject arg) {
        if (arg instanceof RubyFixnum fixnum) return toInt(context, fixnum);
        if (arg instanceof RubyString str) return RegexpOptions.fromByteList(context, str.getByteList()).toJoniOptions();
        if (arg instanceof RubyBoolean) return arg.isTrue() ? RE_OPTION_IGNORECASE : 0;
        if (arg.isNil()) return 0;

        warning(context, str(context.runtime, "expected true or false as ignorecase: ", arg));

        return RE_OPTION_IGNORECASE;
    }

    @Deprecated(since = "10.0")
    public IRubyObject initialize_m(IRubyObject arg) {
        return initialize_m(getCurrentContext(), arg);
    }

    @JRubyMethod(name = "initialize", visibility = Visibility.PRIVATE)
    public IRubyObject initialize_m(ThreadContext context, IRubyObject arg) {
        return arg instanceof RubyRegexp regexp ?
                initializeByRegexp(context, regexp, null) :
                regexpInitializeString(context, arg.convertToString(), new RegexpOptions(), null);
    }

    @Deprecated(since = "10.0")
    public IRubyObject initialize_m(IRubyObject arg0, IRubyObject arg1) {
        return initialize_m(getCurrentContext(), arg0, arg1);
    }

    @JRubyMethod(name = "initialize", visibility = Visibility.PRIVATE, keywords = true)
    public IRubyObject initialize_m(ThreadContext context, IRubyObject arg0, IRubyObject arg1) {
        boolean keywords = (resetCallInfo(context) & ThreadContext.CALL_KEYWORD) != 0;

        IRubyObject timeout;
        RegexpOptions regexpOptions;
        if (keywords) {
            regexpOptions = new RegexpOptions();
            timeout = timeoutFromArg(context, arg1);
            if (arg0 instanceof RubyRegexp regexp) return initializeByRegexp(context, regexp, timeout);
        } else {
            if (arg0 instanceof RubyRegexp && Options.PARSER_WARN_FLAGS_IGNORED.load()) {
                warn(context, "flags ignored");
                return initializeByRegexp(context, (RubyRegexp)arg0, null);
            }
            regexpOptions = RegexpOptions.fromJoniOptions(objectAsJoniOptions(context, arg1));
            timeout = null;
        }

        return regexpInitializeString(context, arg0.convertToString(), regexpOptions, timeout);
    }

    @Deprecated(since = "10.0")
    public IRubyObject initialize_m(IRubyObject arg0, IRubyObject arg1, IRubyObject arg2) {
        return initialize_m(getCurrentContext(), arg0, arg1, arg2);
    }

    @JRubyMethod(name = "initialize", visibility = Visibility.PRIVATE, keywords = true)
    public IRubyObject initialize_m(ThreadContext context, IRubyObject arg0, IRubyObject arg1, IRubyObject arg2) {
        boolean keywords = (resetCallInfo(context) & ThreadContext.CALL_KEYWORD) != 0;

        if (arg0 instanceof RubyRegexp && Options.PARSER_WARN_FLAGS_IGNORED.load()) {
            warn(context, "flags ignored");
            return initializeByRegexp(context, (RubyRegexp)arg0, timeoutFromArg(context, arg2));
        }

        RegexpOptions newOptions = RegexpOptions.fromJoniOptions(objectAsJoniOptions(context, arg1));
        if (!keywords) throw argumentError(context, 3, 1, 2);

        return regexpInitializeString(context, arg0.convertToString(), newOptions, timeoutFromArg(context, arg2));
    }

    private IRubyObject timeoutFromArg(ThreadContext context, IRubyObject arg) {
        return Convert.castAsHash(context, arg).fastARef(asSymbol(context, "timeout"));
    }

    private IRubyObject initializeByRegexp(ThreadContext context, RubyRegexp regexp, IRubyObject timeoutProvided) {
        // Clone and toggle flags since this is no longer a literal regular expression
        // but it did come from one.
        RegexpOptions newOptions = regexp.getOptions(context).clone();
        newOptions.setLiteral(false);
        return regexpInitialize(regexp.str, regexp.getEncoding(), newOptions, timeoutProvided != null ? timeoutProvided : regexp.timeout);
    }

    // rb_reg_initialize_str
    private RubyRegexp regexpInitializeString(ThreadContext context, RubyString str, RegexpOptions options, IRubyObject timeout) {
        if (isLiteral()) throw context.runtime.newFrozenError(this);
        ByteList bytes = str.getByteList();
        Encoding enc = bytes.getEncoding();
        if (options.isEncodingNone()) {
            if (enc != ASCIIEncoding.INSTANCE) {
                if (str.scanForCodeRange() != StringSupport.CR_7BIT) {
                    RegexpSupport.raiseRegexpError(context.runtime, bytes, enc, options, "/.../n has a non escaped non ASCII character in non ASCII-8BIT script");
                }
                enc = ASCIIEncoding.INSTANCE;
            }
        }
        return regexpInitialize(bytes, enc, options, timeout);
    }

    @Deprecated
    public final RubyRegexp regexpInitialize(ByteList bytes, Encoding enc, RegexpOptions options) {
        return regexpInitialize(bytes, enc, options, null);
    }
    // rb_reg_initialize
    public final RubyRegexp regexpInitialize(ByteList bytes, Encoding enc, RegexpOptions options, IRubyObject timeout) {
        Ruby runtime = metaClass.runtime;
        this.options = options;
        this.timeout = processTimeoutArg(runtime.getCurrentContext(), timeout);

        checkFrozen();
        // FIXME: Something unsets this bit, but we aren't...be more permissive until we figure this out
        //if (isLiteral()) throw runtime.newSecurityError("can't modify literal regexp");
        if (pattern != null) throw typeError(runtime.getCurrentContext(), "already initialized regexp");
        if (enc.isDummy()) RegexpSupport.raiseRegexpError(runtime, bytes, enc, options, "can't make regexp with dummy encoding");

        Encoding[]fixedEnc = new Encoding[]{null};
        ByteList unescaped = RegexpSupport.preprocess(runtime, bytes, enc, fixedEnc, RegexpSupport.ErrorMode.RAISE);
        if (fixedEnc[0] != null) {
            if ((fixedEnc[0] != enc && options.isFixed()) ||
               (fixedEnc[0] != ASCIIEncoding.INSTANCE && options.isEncodingNone())) {
                RegexpSupport.raiseRegexpError(runtime, bytes, enc, options, "incompatible character encoding");
            }
            if (fixedEnc[0] != ASCIIEncoding.INSTANCE) {
                options.setFixed(true);
                enc = fixedEnc[0];
            }
        } else if (!options.isFixed()) {
            enc = USASCIIEncoding.INSTANCE;
        }

        if (fixedEnc[0] != null) options.setFixed(true);
        if (options.isEncodingNone()) setEncodingNone();

        pattern = getRegexpFromCache(runtime, unescaped, enc, options);
        assert bytes != null;
        str = bytes;
        return this;
    }

    @JRubyMethod
    public RubyFixnum hash(ThreadContext context) {
        check(context);
        int hash = pattern.getOptions();
        int len = str.getRealSize();
        int p = str.getBegin();
        byte[] bytes = str.getUnsafeBytes();
        while (len-- > 0) {
            hash = hash * 33 + bytes[p++];
        }
        return asFixnum(context, hash + (hash >> 5));
    }

    @JRubyMethod(name = {"==", "eql?"})
    @Override
    public IRubyObject op_equal(ThreadContext context, IRubyObject other) {
        if (this == other) return context.tru;
        if (!(other instanceof RubyRegexp otherRegex)) return context.fals;

        check(context);
        otherRegex.check(context);

        return asBoolean(context, str.equal(otherRegex.str) && options.equals(otherRegex.options));
    }

    @JRubyMethod(name = "~", reads = {LASTLINE}, writes = BACKREF)
    public IRubyObject op_match2(ThreadContext context) {
        IRubyObject line = context.getLastLine();
        if (line instanceof RubyString) {
            int start = searchString(context, (RubyString) line, 0, false);
            if (start >= 0) {
                // set backref for user
                context.updateBackref();

                return asFixnum(context, start);
            }
        }

        // set backref for user
        context.clearBackRef();

        return context.nil;
    }

    /** rb_reg_eqq
     *
     */
    @JRubyMethod(name = "===", writes = BACKREF)
    public IRubyObject eqq(ThreadContext context, IRubyObject arg) {
        arg = operandNoCheck(context, arg);

        if (!arg.isNil()) {
            int start = searchString(context, (RubyString) arg, 0, false);
            if (start >= 0) {
                // set backref for user
                context.updateBackref();

                return context.tru;
            }
        }

        // set backref for user
        context.clearBackRef();

        return context.fals;
    }

    // MRI: rb_reg_match

    @Override
    @JRubyMethod(name = "=~", writes = BACKREF)
    public IRubyObject op_match(ThreadContext context, IRubyObject str) {
        final RubyString[] strp = { null };
        int pos = matchPos(context, str, strp, true, 0);
        if (pos < 0) return context.nil;
        pos = strp[0].subLength(pos);
        return asFixnum(context, pos);
    }

    /** rb_reg_match_m
     *
     */
    @JRubyMethod(name = "match", writes = BACKREF)
    public IRubyObject match_m(ThreadContext context, IRubyObject str, Block block) {
        return matchCommon(context, str, 0, true, block);
    }

    @JRubyMethod(name = "match", writes = BACKREF)
    public IRubyObject match_m(ThreadContext context, IRubyObject str, IRubyObject pos, Block block) {
        return matchCommon(context, str, toInt(context, pos), true, block);
    }

    public final IRubyObject match_m(ThreadContext context, IRubyObject str, boolean useBackref) {
        return matchCommon(context, str, 0, useBackref, Block.NULL_BLOCK);
    }

    @JRubyMethod(name = "match?")
    public IRubyObject match_p(ThreadContext context, IRubyObject str) {
        return matchP(context, str, 0);
    }

    @JRubyMethod(name = "match?")
    public IRubyObject match_p(ThreadContext context, IRubyObject str, IRubyObject pos) {
        return matchP(context, str, toInt(context, pos));
    }

    private IRubyObject matchCommon(ThreadContext context, IRubyObject str, int pos, boolean setBackref, Block block) {
        if (matchPos(context, str, null, setBackref, pos) < 0) {
            return context.nil;
        }

        IRubyObject backref = context.getLocalMatchOrNil();
        if (block.isGiven()) return block.yield(context, backref);
        return backref;
    }

    /**
     * MRI: reg_match_pos
     *
     * @param context thread context
     * @param arg the stringlike to match
     * @param strp an out param to hold the coerced string; ignored if null
     * @param useBackref whether to update the current execution frame's backref
     * @param pos the position from which to start matching
     */
    private int matchPos(ThreadContext context, IRubyObject arg, RubyString[] strp, boolean useBackref, int pos) {
        if (arg == context.nil) {
            context.clearLocalMatch();

            // set backref for user
            if (useBackref) context.updateBackref();

            return -1;
        }

        final RubyString str = operandCheck(context, arg);
        if (strp != null) strp[0] = str;
        if (pos != 0) {
            if (pos < 0) {
                pos += str.strLength();
                if (pos < 0) return pos;
            }
            pos = str.rbStrOffset(pos);
        }

        int result = searchString(context, str, pos, false);

        if (useBackref) context.updateBackref();

        return result;
    }

    private RubyBoolean matchP(ThreadContext context, IRubyObject arg, int pos) {
        if (arg == context.nil) return context.fals;
        RubyString str = arg instanceof RubySymbol sym ? (RubyString) sym.to_s(context) : arg.convertToString();
        return matchP(context, str, pos);
    }

    final RubyBoolean matchP(ThreadContext context, RubyString str, int pos) {
        if (pos != 0) {
            if (pos < 0) {
                pos += str.strLength();
                if (pos < 0) return context.fals;
            }
            pos = str.rbStrOffset(pos);
        }

        final Regex reg = preparePattern(context, str);
        final ByteList strBL = str.getByteList();
        final int beg = strBL.begin();
        final long timeout = getRegexpTimeout(context);

        Matcher matcher = reg.matcherNoRegion(strBL.unsafeBytes(), beg, beg + strBL.realSize(), timeout);

        try {
            final int result = matcherSearch(context, matcher, beg + pos, beg + strBL.realSize(), RE_OPTION_NONE);
            return result == -1 ? context.fals : context.tru;
        } catch (JOniException je) {
            throw context.runtime.newRegexpError(je.getMessage());
        }
    }

    @JRubyMethod(meta = true, name = "timeout=")
    public static IRubyObject timeout_set(ThreadContext context, IRubyObject recv, IRubyObject timeout) {
        context.runtime.setRubyTimeout(processTimeoutArg(context, timeout));
        return timeout;
    }

    private static double MAX_TIMEOUT_VALUE = 18446744073.709553; // ((1<<64)-1) / 1000000000.0

    private static IRubyObject processTimeoutArg(ThreadContext context, IRubyObject timeout) {
        if (timeout == null) return null;
        if (timeout.isNil()) return context.nil;

        RubyFloat converted = timeout.convertToFloat();

        if (converted.isInfinite() || converted.value > MAX_TIMEOUT_VALUE) converted = asFloat(context, MAX_TIMEOUT_VALUE);
        if (converted.value <= 0) throw argumentError(context, "invalid timeout: " + timeout);

        return converted;
    }

    @JRubyMethod(meta = true, name = "timeout")
    public static IRubyObject timeout(ThreadContext context, IRubyObject recv) {
        return context.runtime.getRubyTimeout();
    }

    @JRubyMethod(name = "timeout")
    public IRubyObject timeout(ThreadContext context) {
        return timeout == null ? context.nil : timeout;
    }

    // float s in ns
    private long getRegexpTimeout(ThreadContext context) {
        IRubyObject timeout = this.timeout;
        if (timeout != null && timeout.isNil()) return -1; // local override to ignore global timeout.
        if (timeout == null) timeout = context.runtime.getRubyTimeout();

        return timeout.isNil() ? -1 : (long) (timeout.convertToFloat().asDouble(context) * 1_000_000_000);
    }

    /**
     * MRI: rb_reg_search
     *
     * This version uses current thread context to hold the resulting match data.
     */
    public final int search(ThreadContext context, RubyString str, int pos, boolean reverse) {
        int result = searchString(context, str, pos, reverse);

        // set backref for user
        context.updateBackref();

        return result;
    }

    final boolean startsWith(ThreadContext context, RubyString str) {
        final ByteList strBL = str.getByteList();
        final int beg = strBL.begin();
        final Regex reg = preparePattern(context, str);

        final Matcher matcher = reg.matcher(strBL.unsafeBytes(), beg, beg + strBL.realSize());

        try {
            int result = matcherMatch(context, matcher, beg, beg + strBL.realSize(), RE_OPTION_NONE);
            if (result == -1) {
                context.setLocalMatch(null);

                // set backref for user
                context.updateBackref();

                return false;
            }

            RubyMatchData match = context.getLocalMatch();
            if (match == null || match.used()) {
                match = createMatchData(context, str, matcher, reg);
            } else {
                match.initMatchData(str, matcher, reg);
            }

            match.regexp = this;

            context.setLocalMatch(match);

            // set backref for the user (this may go away, https://bugs.ruby-lang.org/issues/17771)
            context.updateBackref();

            return true;
        } catch (JOniException je) {
            throw context.runtime.newRegexpError(je.getMessage());
        }
    }

    @Deprecated
    public final RubyBoolean startWithP(ThreadContext context, RubyString str) {
        return startsWith(context, str) ? context.tru : context.fals;
    }

    /**
     * Search the given string with this Regexp.
     *
     * MRI: rb_reg_search0 without backref updating
     */
    public final int searchString(ThreadContext context, RubyString str, int pos, boolean reverse) {
        final ByteList strBL = str.getByteList();
        final int beg = strBL.begin();
        int range = beg;

        if (pos > str.size() || pos < 0) {
            context.setLocalMatch(null);

            return -1;
        }

        final Regex reg = preparePattern(context, str);

        if (!reverse) range += str.size();

        final long timeout = getRegexpTimeout(context);
        final Matcher matcher = reg.matcher(strBL.unsafeBytes(), beg, beg + strBL.realSize(), timeout);

        try {
            int result = matcherSearch(context, matcher, beg + pos, range, RE_OPTION_NONE);
            if (result == -1) {
                context.setLocalMatch(null);

                return -1;
            }

            RubyMatchData match = context.getLocalMatch();
            if (match == null || match.used()) {
                match = createMatchData(context, str, matcher, reg);
            } else {
                match.initMatchData(str, matcher, reg);
            }

            match.regexp = this;

            context.setLocalMatch(match);

            return result;
        } catch (JOniException je) {
            throw context.runtime.newRegexpError(je.getMessage());
        }
    }

    static RubyMatchData createMatchData(ThreadContext context, RubyString str, Matcher matcher, Regex pattern) {
        final RubyMatchData match = new RubyMatchData(context.runtime);
        match.initMatchData(str, matcher, pattern);
        return match;
    }

    static RubyMatchData createMatchData(ThreadContext context, RubyString str, int pos, RubyString pattern) {
        final RubyMatchData match = new RubyMatchData(context.runtime);
        match.initMatchData(str, pos, pattern);
        return match;
    }

    @JRubyMethod
    public IRubyObject options(ThreadContext context) {
        return asFixnum(context, getOptions(context).toOptions());
    }

    @Deprecated
    public IRubyObject options() {
        return options(getCurrentContext());
    }

    @JRubyMethod(name = "casefold?")
    public IRubyObject casefold_p(ThreadContext context) {
        return asBoolean(context, getOptions(context).isIgnorecase());
    }

    /** rb_reg_source
     *
     */
    @JRubyMethod
    public IRubyObject source(ThreadContext context) {
        check(context);
        var enc = pattern == null ? str.getEncoding() : pattern.getEncoding();
        ByteList newStr = str.dup();
        newStr.setEncoding(enc);
        return newString(context, newStr);
    }

    @Deprecated
    public IRubyObject source() {
        return source(getCurrentContext());
    }

    public ByteList rawSource() {
        return str;
    }

    public final int length() {
        return str.getRealSize();
    }

    /** rb_reg_inspect
     *
     */
    @Override
    @JRubyMethod(name = "inspect")
    public IRubyObject inspect(ThreadContext context) {
        return pattern == null ?
                anyToString() :
                newString(context, RegexpSupport.regexpDescription(context.runtime, str, options, str.getEncoding()));
    }

    private final static int EMBEDDABLE = RE_OPTION_MULTILINE|RE_OPTION_IGNORECASE|RE_OPTION_EXTENDED;

    @Deprecated(since = "10.0")
    public RubyString to_s() {
        return to_s(getCurrentContext());
    }

    @Override
    @JRubyMethod
    public RubyString to_s(ThreadContext context) {
        check(context);

        RegexpOptions newOptions = options.clone();
        int p = str.getBegin();
        int len = str.getRealSize();
        byte[] bytes = str.getUnsafeBytes();

        ByteList result = new ByteList(len);
        result.append((byte)'(').append((byte)'?');

        again: do {
            if (len >= 4 && bytes[p] == '(' && bytes[p + 1] == '?') {
                boolean err = true;
                p += 2;
                if ((len -= 2) > 0) {
                    do {
                        if (bytes[p] == 'm') {
                            newOptions.setMultiline(true);
                        } else if (bytes[p] == 'i') {
                            newOptions.setIgnorecase(true);
                        } else if (bytes[p] == 'x') {
                            newOptions.setExtended(true);
                        } else {
                            break;
                        }
                        p++;
                    } while (--len > 0);
                }
                if (len > 1 && bytes[p] == '-') {
                    ++p;
                    --len;
                    do {
                        if (bytes[p] == 'm') {
                            newOptions.setMultiline(false);
                        } else if (bytes[p] == 'i') {
                            newOptions.setIgnorecase(false);
                        } else if (bytes[p] == 'x') {
                            newOptions.setExtended(false);
                        } else {
                            break;
                        }
                        p++;
                    } while (--len > 0);
                }

                if (bytes[p] == ')') {
                    --len;
                    ++p;
                    continue again;
                }

                if (bytes[p] == ':' && bytes[p + len - 1] == ')') {
                    try {
                        new Regex(bytes, ++p, p + (len -= 2), Option.DEFAULT, str.getEncoding(), Syntax.DEFAULT, WarnCallback.NONE);
                        err = false;
                    } catch (JOniException e) {
                        err = true;
                    }
                }

                if (err) {
                    newOptions = options;
                    p = str.getBegin();
                    len = str.getRealSize();
                }
            }

            RegexpSupport.appendOptions(result, newOptions);

            if (!newOptions.isEmbeddable()) {
                result.append((byte)'-');
                if (!newOptions.isMultiline()) result.append((byte)'m');
                if (!newOptions.isIgnorecase()) result.append((byte)'i');
                if (!newOptions.isExtended()) result.append((byte)'x');
            }
            result.append((byte)':');
            Encoding enc = str.getEncoding();
            RegexpSupport.appendRegexpString(context.runtime, result, bytes, p, len, enc, null);

            result.append((byte)')');
            return newString(context, result, getEncoding());
        } while (true);
    }

    /**
     * returns all names in a regexp pattern as id (8859_1) strings
     * @return array of id strings.
     */
    public String[] getNames() {
        int nameLength = pattern.numberOfNames();
        if (nameLength == 0) return EMPTY_STRING_ARRAY;

        String[] names = new String[nameLength];
        int j = 0;
        for (Iterator<NameEntry> i = pattern.namedBackrefIterator(); i.hasNext();) {
            NameEntry e = i.next();
            names[j++] = new String(e.name, e.nameP, e.nameEnd - e.nameP).intern();
        }

        return names;
    }

    /** rb_reg_names
     *
     */
    @JRubyMethod
    public IRubyObject names(ThreadContext context) {
        check(context);

        if (pattern.numberOfNames() == 0) return newEmptyArray(context);

        var ary = RubyArray.newBlankArray(context, pattern.numberOfNames());
        int index = 0;
        for (Iterator<NameEntry> i = pattern.namedBackrefIterator(); i.hasNext();) {
            NameEntry e = i.next();
            RubyString name = RubyString.newStringShared(context.runtime, e.name, e.nameP, e.nameEnd - e.nameP, pattern.getEncoding());
            ary.storeInternal(context, index++, name);
        }
        return ary;
    }

    /** rb_reg_named_captures
     *
     */
    @JRubyMethod
    public IRubyObject named_captures(ThreadContext context) {
        check(context);
        RubyHash hash = newHash(context);
        if (pattern.numberOfNames() == 0) return hash;

        for (Iterator<NameEntry> i = pattern.namedBackrefIterator(); i.hasNext();) {
            NameEntry e = i.next();
            int[] backrefs = e.getBackRefs();
            RubyArray ary = RubyArray.newBlankArrayInternal(context.runtime, backrefs.length);

            for (int idx = 0; idx<backrefs.length; idx++) {
                ary.storeInternal(context, idx, asFixnum(context, backrefs[idx]));
            }
            RubyString name = RubyString.newStringShared(context.runtime, e.name, e.nameP, e.nameEnd - e.nameP);
            hash.fastASet(name.freeze(context), ary);
        }
        return hash;
    }

    @JRubyMethod
    public IRubyObject encoding(ThreadContext context) {
        Encoding enc = pattern == null ? str.getEncoding() : pattern.getEncoding();
        return encodingService(context).getEncoding(enc);
    }

    @JRubyMethod(name = "fixed_encoding?")
    public IRubyObject fixed_encoding_p(ThreadContext context) {
        return asBoolean(context, options.isFixed());
    }

    private record RegexpArgs(RubyString string, int options, IRubyObject timeout) {}

    // MRI: reg_extract_args - This does not break the regexp into a String value since it will never used if the first
    // argument is a Regexp.  This also is true of MRI so I am not sure why they do the string part.
    private static RegexpArgs extractRegexpArgs(ThreadContext context, IRubyObject[] args) {
        int callInfo = resetCallInfo(context);
        int length = args.length;

        IRubyObject timeout = null;
        if ((callInfo & ThreadContext.CALL_KEYWORD) != 0) {
            length--;
            RubyHash opts = Convert.castAsHash(context, args[args.length - 1]);
            timeout = opts.fastARef(asSymbol(context, "timeout"));
        }

        RubyString string;
        int opts = 0;
        if (args[0] instanceof RubyRegexp) {
            if (length > 1) warn(context, "flags ignored");
            string = null;
        } else {
            if (length > 1) opts = objectAsJoniOptions(context, args[1]);
            string = args[0].convertToString();
        }

        return new RegexpArgs(string, opts, timeout);
    }

    @JRubyMethod(name = "linear_time?", meta = true, required = 1, optional = 1)
    public static IRubyObject linear_time_p(ThreadContext context, IRubyObject recv, IRubyObject[] args) {
        RegexpArgs regexpArgs = extractRegexpArgs(context, args);
        RubyRegexp regexp = args[0] instanceof RubyRegexp reg ?
                reg : newRegexpFromStr(context.runtime, regexpArgs.string, regexpArgs.options);

        regexp.check(context);

        Regex pattern = regexp.pattern;

        // Regexp.allocate will make a regexp instance with no pattern.
        return pattern != null && pattern.isLinear() ? context.tru : context.fals;
    }

    @Deprecated(since = "10.0")
    public static IRubyObject nth_match(int nth, IRubyObject match) {
        return nth_match(((RubyBasicObject) match).getCurrentContext(), nth, match);
    }

    /** rb_reg_nth_match
    *
    */
    public static IRubyObject nth_match(ThreadContext context, int nth, IRubyObject matchArg) {
        return matchArg instanceof RubyMatchData match ?
                nth_match(context, nth, match) : context.nil;
    }

    static IRubyObject nth_match(ThreadContext context, int nth, RubyMatchData match) {
        match.check(context);

        final int start, end;
        if (match.regs == null) {
            if (nth >= 1 || (nth < 0 && ++nth <= 0)) return context.nil;
            start = match.begin;
            end = match.end;
        } else {
            if (nth >= match.regs.getNumRegs() || (nth < 0 && (nth+=match.regs.getNumRegs()) <= 0)) {
                return context.nil;
            }
            start = match.regs.getBeg(nth);
            end = match.regs.getEnd(nth);
        }

        return start == -1 ?
                context.nil : match.str.makeSharedString(context.runtime, start, end - start);
    }

    @Deprecated(since = "10.0")
    public static IRubyObject last_match(IRubyObject match) {
        return last_match(((RubyBasicObject) match).getCurrentContext(), match);
    }

    /** rb_reg_last_match
     *
     */
    public static IRubyObject last_match(ThreadContext context, IRubyObject match) {
        return nth_match(context, 0, match);
    }


    @Deprecated(since = "10.0")
    public static IRubyObject match_pre(IRubyObject match) {
        return match_pre(((RubyBasicObject) match).getCurrentContext(), match);
    }

    /** rb_reg_match_pre
     *
     */
    public static IRubyObject match_pre(ThreadContext context, IRubyObject matchArg) {
        if (!(matchArg instanceof RubyMatchData match)) return context.nil;

        match.check(context);

        return match.begin == -1 ?
                context.nil : match.str.makeShared(context.runtime, 0,  match.begin);
    }

    @Deprecated(since = "10.0")
    public static IRubyObject match_post(IRubyObject match) {
        return match_post(((RubyBasicObject) match).getCurrentContext(), match);
    }

    /** rb_reg_match_post
     *
     */
    public static IRubyObject match_post(ThreadContext context, IRubyObject matchArg) {
        if (!(matchArg instanceof RubyMatchData match)) return context.nil;

        match.check(context);

        return match.begin != -1 ?
                match.str.makeShared(context.runtime, match.end, match.str.getByteList().getRealSize() - match.end) :
                context.nil;
    }

    @Deprecated(since = "10.0")
    public static IRubyObject match_last(IRubyObject match) {
        return match_last(((RubyBasicObject) match).getCurrentContext(), match);
    }

    /** rb_reg_match_last
     *
     */
    public static IRubyObject match_last(ThreadContext context, IRubyObject matchArg) {
        if (!(matchArg instanceof RubyMatchData match)) return matchArg;

        match.check(context);

        if (match.regs == null || match.regs.getBeg(0) == -1) return context.nil;

        int i;
        for (i = match.regs.getNumRegs() - 1; match.regs.getBeg(i) == -1 && i > 0; i--);

        return i == 0 ? context.nil : nth_match(context, i, match);
    }

    // MRI: ASCGET macro from rb_reg_regsub
    private static final int ASCGET(boolean acompat, byte[] sBytes, int s, int e, int[] cl, Encoding strEnc) {
        if (acompat) {
            cl[0] = 1;
            return Encoding.isAscii(sBytes[s]) ? sBytes[s] & 0xFF : -1;
        } else {
            return EncodingUtils.encAscget(sBytes, s, e, cl, strEnc);
        }
    }

    static RubyString regsub(ThreadContext context, RubyString str, RubyString src, Regex pattern, Matcher matcher) {
        return regsub(context, str, src, pattern, matcher.getRegion(), matcher.getBegin(), matcher.getEnd());
    }

    // rb_reg_regsub
    static RubyString regsub(ThreadContext context, RubyString str, RubyString src, Regex pattern, Region regs,
                             final int begin, final int end) {
        RubyString val = null;
        int no = 0, clen[] = {0};
        Encoding strEnc = EncodingUtils.encGet(context, str);
        Encoding srcEnc = EncodingUtils.encGet(context, src);
        boolean acompat = EncodingUtils.encAsciicompat(strEnc);

        ByteList bs = str.getByteList();
        ByteList srcbs = src.getByteList();
        byte[] sBytes = bs.getUnsafeBytes();
        int s = bs.getBegin();
        int p = s;
        int e = p + bs.getRealSize();

        while (s < e) {
            int c = ASCGET(acompat, sBytes, s, e, clen, strEnc);

            if (c == -1) {
                s += StringSupport.length(strEnc, sBytes, s, e);
                continue;
            }
            int ss = s;
            s += clen[0];

            if (c != '\\' || s == e) continue;

            if (val == null) val = newString(context, new ByteList(ss - p));
            EncodingUtils.encStrBufCat(context.runtime, val, sBytes, p, ss - p, strEnc);

            c = ASCGET(acompat, sBytes, s, e, clen, strEnc);

            if (c == -1) {
                s += StringSupport.length(strEnc, sBytes, s, e);
                EncodingUtils.encStrBufCat(context.runtime, val, sBytes, ss, s - ss, strEnc);
                p = s;
                continue;
            }
            s += clen[0];

            p = s;
            switch (c) {
            case '1': case '2': case '3': case '4':
            case '5': case '6': case '7': case '8': case '9':
                if (pattern.noNameGroupIsActive(Syntax.RUBY)) {
                    no = c - '0';
                    break;
                } else {
                    continue;
                }
            case 'k':
                if (s < e && ASCGET(acompat, sBytes, s, e, clen, strEnc) == '<') {
                    int name = s + clen[0];
                    int nameEnd = name;
                    while (nameEnd < e) {
                        c = ASCGET(acompat, sBytes, nameEnd, e, clen, strEnc);
                        if (c == '>') break;
                        nameEnd += c == -1 ? StringSupport.length(strEnc, sBytes, nameEnd, e) : clen[0];
                    }
                    if (nameEnd < e) {
                        try {
                            no = pattern.nameToBackrefNumber(sBytes, name, nameEnd, regs);
                        } catch (JOniException je) {
                            throw indexError(context, je.getMessage());
                        }
                        p = s = nameEnd + clen[0];
                        break;
                    } else {
                        throw runtimeError(context, "invalid group name reference format");
                    }
                }

                EncodingUtils.encStrBufCat(context.runtime, val, sBytes, ss, s - ss, strEnc);
                continue;
            case '0': case '&':
                no = 0;
                break;
            case '`':
                EncodingUtils.encStrBufCat(context.runtime, val, srcbs.getUnsafeBytes(), srcbs.getBegin(), begin, srcEnc);
                continue;
            case '\'':
                EncodingUtils.encStrBufCat(context.runtime, val, srcbs.getUnsafeBytes(), srcbs.getBegin() + end, srcbs.getRealSize() - end, srcEnc);
                continue;
            case '+':
                if (regs != null) {
                    no = regs.getNumRegs() - 1;
                    while (regs.getBeg(no) == -1 && no > 0) no--;
                }
                if (no == 0) continue;
                break;
            case '\\':
                EncodingUtils.encStrBufCat(context.runtime, val, sBytes, s - clen[0], clen[0], strEnc);
                continue;
            default:
                EncodingUtils.encStrBufCat(context.runtime, val, sBytes, ss, s - ss, strEnc);
                continue;
            }

            if (regs != null) {
                if (no >= 0) {
                    if (no >= regs.getNumRegs()) continue;
                    if (regs.getBeg(no) == -1) continue;
                    EncodingUtils.encStrBufCat(context.runtime, val, srcbs.getUnsafeBytes(), srcbs.getBegin() + regs.getBeg(no), regs.getEnd(no) - regs.getBeg(no), srcEnc);
                }
            } else {
                if (no != 0 || begin == -1) continue;
                EncodingUtils.encStrBufCat(context.runtime, val, srcbs.getUnsafeBytes(), srcbs.getBegin() + begin, end - begin, srcEnc);
            }
        }

        if (val == null) return str;
        if (p < e) EncodingUtils.encStrBufCat(context.runtime, val, sBytes, p, e - p, strEnc);
        return val;
    }

    final int adjustStartPos(ThreadContext context, RubyString str, int pos, boolean reverse) {
        check(context);
        return adjustStartPosInternal(str, pattern.getEncoding(), pos, reverse);
    }

    private static int adjustStartPosInternal(RubyString str, Encoding enc, int pos, boolean reverse) {
        ByteList value = str.getByteList();
        int len = value.getRealSize();
        if (pos > 0 && enc.maxLength() != 1 && pos < len) {
            int start = value.getBegin();
            if ((reverse ? -pos : len - pos) > 0) {
                return enc.rightAdjustCharHead(value.getUnsafeBytes(), start, start + pos, start + len) - start;
            } else {
                return enc.leftAdjustCharHead(value.getUnsafeBytes(), start, start + pos, start + len) - start;
            }
        }

        return pos;
    }

    private static IRubyObject operandNoCheck(ThreadContext context, IRubyObject str) {
        return regOperand(context, str, false);
    }

    private static RubyString operandCheck(ThreadContext context, IRubyObject str) {
        return (RubyString) regOperand(context, str, true);
    }

    // MRI: reg_operand
    private static IRubyObject regOperand(ThreadContext context, IRubyObject str, boolean check) {
        if (str instanceof RubySymbol sym) return sym.to_s(context);
        return check ? str.convertToString() : str.checkStringType();
    }

    @Deprecated(forRemoval = true)
    @SuppressWarnings("removal")
    public static RubyRegexp unmarshalFrom(org.jruby.runtime.marshal.UnmarshalStream input) throws java.io.IOException {
        return newRegexp(input.getRuntime(), input.unmarshalString(), RegexpOptions.fromJoniOptions(input.readSignedByte()));
    }

    @Deprecated(since = "10.0", forRemoval = true)
    @SuppressWarnings("removal")
    public static void marshalTo(RubyRegexp regexp, org.jruby.runtime.marshal.MarshalStream output) throws java.io.IOException {
        var context = regexp.getRuntime().getCurrentContext();
        output.registerLinkTarget(context, regexp);
        output.writeString(regexp.str);

        int options = regexp.pattern.getOptions() & EMBEDDABLE;

        if (regexp.getOptions(context).isFixed()) options |= RE_FIXED;

        output.writeByte(options);
    }

    public static void marshalTo(ThreadContext context, RubyRegexp regexp, MarshalDumper output, RubyOutputStream out) {
        output.registerLinkTarget(regexp);
        output.writeString(out, regexp.str);

        int options = regexp.pattern.getOptions() & EMBEDDABLE;

        if (regexp.getOptions(context).isFixed()) options |= RE_FIXED;

        output.writeByte(out, options);
    }

    @Deprecated
    public final int search(ThreadContext context, RubyString str, int pos, boolean reverse, IRubyObject[] holder) {
        int result = searchString(context, str, pos, reverse);
        if (holder != null) {
            holder[0] = context.getLocalMatchOrNil();
        } else {
            context.setBackRef(context.getLocalMatchOrNil());
        }
        return result;
    }

    @Deprecated
    public static IRubyObject getBackRef(ThreadContext context) {
        return context.getBackRef();
    }

    @Deprecated(since = "10.0")
    public boolean isSimpleString() {
        return isSimpleString(getCurrentContext());
    }
    /**
     * Is the pattern itself a simple US-ASCII string which can be used in simple string searches and
     * can be used outside of the regexp engine?
     *
     */
    public boolean isSimpleString(ThreadContext context) {
        return isLiteral() &&
                getEncoding().isAsciiCompatible() &&
                RubyString.scanForCodeRange(str) == CR_7BIT &&
                !getOptions(context).isIgnorecase() &&
                ((str.realSize() == 1 &&
                        str.charAt(0) != '.' &&
                        str.charAt(0) != '^' &&
                        str.charAt(0) != '$' &&
                        str.charAt(0) != ' ') ||
                isExact(str));
        // FIXME ' ' is for awk split detection this should be in split code perhaps.
    }

    // FIXME: This should be something within joni which says it is a simple text string and not something requiring a regexp.
    // Assumes 7bit source
    private boolean isExact(ByteList str) {
        int size = str.realSize();
        byte[] bytes = str.unsafeBytes();
        int begin = str.begin();

        for (int i = 0; i < size; i++) {
            switch (bytes[begin + i]) {
                case '|':
                case '.':
                case '*':
                case '[':
                case '(':
                case '+':
                case '?':
                case '{':
                case '\\':
                case '^':
                case '$':
                    return false;
            }
        }

        return true;
    }
}
