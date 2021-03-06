/*
 * Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */

package com.gemstone.gemfire.internal;

import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.distributed.internal.DistributionConfigImpl;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.*;

/**
 * Utility class to print banner information at manager startup.
 */
public class Banner {

  private Banner() {
    // everything is static so don't allow instance creation
  }

  private static void prettyPrintPath(String path, PrintWriter out) {
    if (path != null) {
      StringTokenizer st =
          new StringTokenizer(path, System.getProperty("path.separator"));
      while (st.hasMoreTokens()) {
        out.println("  " + st.nextToken());
      }
    }
  }

  /**
   * Print information about this process to the specified stream.
   *
   * @param args possibly null list of command line arguments
   */
  private static void print(PrintWriter out, String args[]) {
    Map sp = new TreeMap((Properties)System.getProperties().clone()); // fix for 46822
    int processId = -1;
    final String SEPERATOR = "---------------------------------------------------------------------------";
    try {
      processId = OSProcess.getId();
    } catch (Throwable t) {
      Error err;
      if (t instanceof Error && SystemFailure.isJVMFailureError(
          err = (Error)t)) {
        SystemFailure.initiateFailure(err);
        // If this ever returns, rethrow the error. We're poisoned
        // now, so don't let this thread continue.
        throw err;
      }
      // Whenever you catch Error or Throwable, you must also
      // check for fatal JVM error (see above).  However, there is
      // _still_ a possibility that you are dealing with a cascading
      // error condition, so you also need to check to see if the JVM
      // is still usable:
      SystemFailure.checkFailure();
    }
    out.println();

    GemFireVersion.getProductName();

    out.println(SEPERATOR);
    out.println();
    out.println("  Copyright (c) 2017 SnappyData, Inc. All rights reserved.");
    out.println();
    out.println("  Licensed under the Apache License, Version 2.0 (the \"License\"); you");
    out.println("  may not use this file except in compliance with the License. You");
    out.println("  may obtain a copy of the License at");
    out.println();
    out.println("  http://www.apache.org/licenses/LICENSE-2.0");
    out.println();
    out.println("  Unless required by applicable law or agreed to in writing, software");
    out.println("  distributed under the License is distributed on an \"AS IS\" BASIS,");
    out.println("  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or");
    out.println("  implied. See the License for the specific language governing");
    out.println("  permissions and limitations under the License. See accompanying");
    out.println("  LICENSE file.");
    out.println();
    out.println(SEPERATOR);

    GemFireVersion.print(out);

    out.println("Process ID: " + processId);
    out.println("User: " + sp.get("user.name"));
    sp.remove("user.name");
    sp.remove("os.name");
    sp.remove("os.arch");
    out.println("Current dir: " + sp.get("user.dir"));
    sp.remove("user.dir");
    out.println("Home dir: " + sp.get("user.home"));
    sp.remove("user.home");
    List<String> allArgs = new ArrayList<String>();
    {
      RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
      if (runtimeBean != null) {
        allArgs.addAll(runtimeBean.getInputArguments()); // fixes  45353
      }
    }

    if (args != null && args.length != 0) {
      for (int i = 0; i < args.length; i++) {
        allArgs.add(args[i]);
      }
    }
    if (!allArgs.isEmpty()) {
      out.println("Command Line Parameters:");
      for (String arg : allArgs) {
        out.println("  " + arg);
      }
    }
    out.println("Class Path:");
    prettyPrintPath((String)sp.get("java.class.path"), out);
    sp.remove("java.class.path");
    out.println("Library Path:");
    prettyPrintPath((String)sp.get("java.library.path"), out);
    sp.remove("java.library.path");

    if (Boolean.getBoolean("gemfire.disableSystemPropertyLogging")) {
      out.println("System property logging disabled.");
    } else {
      out.println("System Properties:");
      Iterator it = sp.entrySet().iterator();
      while (it.hasNext()) {
        Map.Entry me = (Map.Entry)it.next();
        String key = me.getKey().toString();
        // [sumedh] Filter out the security properties since they may contain
        // sensitive information.
        if (!key.startsWith(DistributionConfig.GEMFIRE_PREFIX
            + DistributionConfig.SECURITY_PREFIX_NAME)
            && !key.startsWith(DistributionConfigImpl.SECURITY_SYSTEM_PREFIX
            + DistributionConfig.SECURITY_PREFIX_NAME)
            && !key.toLowerCase().contains("password") /* bug 45381 */) {
          String val = me.getValue().toString();
          if (key.toLowerCase().contains("sun.java.command")) { // SNAP-1660
            val = val.replaceAll("password=\\S+", "password=********");
          }
          out.println("    " + key + " = " + val);
        } else {
          out.println("    " + key + " = " + "********");
        }
      }
    }
    out.println(SEPERATOR);
  }

  /**
   * Return a string containing the banner information.
   *
   * @param args possibly null list of command line arguments
   */
  public static String getString(String args[]) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    print(pw, args);
    pw.close();
    return sw.toString();
  }
}
