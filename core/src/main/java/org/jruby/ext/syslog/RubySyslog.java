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

package org.jruby.ext.syslog;

import org.jruby.Ruby;
import org.jruby.RubyObject;
import org.jruby.RubyModule;
import org.jruby.RubyClass;
import org.jruby.anno.JRubyModule;
import jnr.constants.platform.Syslog;
import org.jruby.runtime.ThreadContext;

import static org.jruby.api.Define.defineModule;

@JRubyModule(name="Syslog")
public class RubySyslog extends RubyObject {
    public static RubyModule createSyslog(ThreadContext context) {
        RubyModule Syslog = defineModule(context, "Syslog");

        Syslog.defineModuleUnder(context, "Constants").defineConstantsFrom(context, Syslog.class);

        return Syslog;
    }

    public RubySyslog(Ruby runtime, RubyClass type) {
        super(runtime, type);
    }
}
