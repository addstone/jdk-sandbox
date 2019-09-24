/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import java.nio.file.Path;
import jdk.jpackage.test.Test;
import jdk.jpackage.test.PackageTest;
import jdk.jpackage.test.PackageType;
import jdk.jpackage.test.JPackageCommand;

/**
 * Test --app-image parameter. The output installer should provide the same
 * functionality as the default installer (see description of the default
 * installer in SimplePackageTest.java)
 */

/*
 * @test
 * @summary jpackage with --app-image
 * @library ../helpers
 * @modules jdk.jpackage/jdk.jpackage.internal
 * @run main/othervm/timeout=360 -Xmx512m AppImagePackageTest
 */
public class AppImagePackageTest {

    public static void main(String[] args) {
        Test.run(args, () -> {
            Path appimageOutput = Path.of("appimage");

            JPackageCommand appImageCmd = JPackageCommand.helloAppImage()
                    .setArgumentValue("--dest", appimageOutput)
                    .addArguments("--package-type", "app-image");

            PackageTest packageTest = new PackageTest();
            if (packageTest.getAction() == PackageTest.Action.CREATE) {
                appImageCmd.execute();
            }

            packageTest.addInitializer(cmd -> {
                Path appimageInput = appimageOutput.resolve(appImageCmd.name());

                if (PackageType.MAC.contains(cmd.packageType())) {
                    // Why so complicated on macOS?
                    appimageInput = Path.of(appimageInput.toString() + ".app");
                    cmd.addArguments("--identifier", appImageCmd.name());
                }

                cmd.addArguments("--app-image", appimageInput);
                cmd.removeArgument("--input");
            }).addBundleDesktopIntegrationVerifier(false).run();
        });
    }
}
