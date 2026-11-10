#!/usr/bin/env python3
"""Regenerate the real R8 classfile fixture and capture its actual failing JVM trace."""
import os
from pathlib import Path
import subprocess
import sys

root=Path(__file__).resolve().parent.parent
jdk=Path(os.environ.get('JAVA_HOME',str(Path.home()/'.jdks/jbr-21.0.11')))
r8=Path(sys.argv[1]).resolve() if len(sys.argv)==2 else root/'server/build/install/server/lib/r8-9.5.29.jar'
fixture=root/'retrace/src/test/resources/generated'
work=root/'build/generated-r8-fixture'
classes=work/'classes'
classes.mkdir(parents=True,exist_ok=True)
java=str(jdk/'bin/java')
version=subprocess.check_output([java,'-cp',str(r8),'com.android.tools.r8.R8','--version'],text=True)
assert '9.5.29' in version,version
subprocess.run([str(jdk/'bin/javac'),'-g','--release','8','-d',str(classes),*[str(p) for p in (fixture/'source/sample').glob('*.java')]],check=True)
subprocess.run([str(jdk/'bin/jar'),'--create','--file',str(work/'input.jar'),'-C',str(classes),'.'],check=True)
subprocess.run([java,'-cp',str(r8),'com.android.tools.r8.R8','--classfile','--release','--lib',str(jdk),'--pg-conf',str(fixture/'rules.pro'),'--pg-map-output',str(fixture/'mapping.txt'),'--output',str(work/'obfuscated.jar'),str(work/'input.jar')],check=True)
crash=subprocess.run([java,'-cp',str(work/'obfuscated.jar'),'sample.Main'],capture_output=True,text=True)
assert crash.returncode==1 and 'generated fixture' in crash.stderr,crash.stderr
(fixture/'trace.txt').write_text(crash.stderr)
print('Generated mapping and actual obfuscated JVM trace with '+version.strip())
