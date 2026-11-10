# Real R8-generated fixture

Generated with the project's pinned Google R8 9.5.29 using the Java classfile
backend. The source and keep rules are included. `trace.txt` is the actual stderr
from executing the obfuscated classfile output, which exits with an exception.
`mapping.txt` is unmodified R8 output, including its mapping-version/hash metadata.
No uploaded APK is executed to produce this fixture.

Regenerate from the project root with JDK 21:

```sh
python3 scripts/generate-r8-fixture.py /path/to/r8-9.5.29.jar
```

With no argument the script uses the built server distribution's R8 JAR.
Compiler outputs are written under ignored build/generated-r8-fixture; only the
source, rules, mapping and captured trace are retained as test resources.
