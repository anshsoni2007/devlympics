import os, sys, subprocess, zipfile, shutil

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MOBILE_DIR = os.path.join(ROOT, "mobile")
APP_DIR = os.path.join(MOBILE_DIR, "app")
SCRATCH = os.path.join(ROOT, "scratch", "build")
USER_HOME = os.path.expanduser("~")
JAVA_HOME_BIN = os.path.join(USER_HOME, ".jdk17", "bin")
JAVAC = os.path.join(JAVA_HOME_BIN, "javac.exe")
JAVA_EXE = os.path.join(JAVA_HOME_BIN, "java.exe")
KOTLINC = os.path.join(USER_HOME, ".kotlinc", "kotlinc", "bin", "kotlinc.bat")
BUNDLETOOL = os.path.join(USER_HOME, r".gradle\caches\modules-2\files-2.1\com.android.tools.build\bundletool\1.18.3\8d7bee8e57a4158a872ed99190a60a498c2adc8d\bundletool-1.18.3.jar")
APKSIG_JAR = os.path.join(USER_HOME, r".gradle\caches\modules-2\files-2.1\com.android.tools.build\apksig\9.0.0\2881101f6a9d0baf6a341926ef0ff08c98a5d13b\apksig-9.0.0.jar")
ADB = os.path.join(os.environ.get("LOCALAPPDATA", ""), r"Android\platform-tools\adb.exe")
UNITY_ADB = r"C:\Program Files\Unity\Hub\Editor\6000.6.0f1\Editor\Data\PlaybackEngines\AndroidPlayer\SDK\platform-tools\adb.exe"

def run(cmd, env=None, cwd=ROOT):
    e = os.environ.copy()
    if env: e.update(env)
    print(f"RUN: {cmd}")
    res = subprocess.run(cmd, shell=True, env=e, cwd=cwd)
    if res.returncode != 0:
        raise RuntimeError(f"Command failed with code {res.returncode}: {cmd}")

def build():
    os.makedirs(SCRATCH, exist_ok=True)
    classes_dir = os.path.join(SCRATCH, "classes")
    dex_dir = os.path.join(SCRATCH, "dex")
    if os.path.exists(classes_dir): shutil.rmtree(classes_dir)
    if os.path.exists(dex_dir): shutil.rmtree(dex_dir)
    os.makedirs(classes_dir, exist_ok=True)
    os.makedirs(dex_dir, exist_ok=True)

    android_jar = os.path.join(MOBILE_DIR, "android.jar")
    existing_classes = os.path.join(APP_DIR, r"build\intermediates\javac\debug\compileDebugJavaWithJavac\classes")
    env = {"PATH": f"{JAVA_HOME_BIN};{os.environ['PATH']}"}

    # 1. Compile Java sources (LocalTransport, FrameIO, ItpPacket, etc.)
    java_src_dir = os.path.join(APP_DIR, r"src\main\java\in\itantra\mobile")
    java_sources = []
    for f in os.listdir(java_src_dir):
        if f.endswith(".java") and not f.endswith(".bak"):
            java_sources.append(os.path.join(java_src_dir, f))
    
    javac_args = " ".join([f'"{f}"' for f in java_sources])
    print(f"Compiling {len(java_sources)} Java source files with javac...")
    javac_cp = f"{android_jar};{existing_classes}"
    run(f'"{JAVAC}" -cp "{javac_cp}" -d "{classes_dir}" {javac_args}', env=env)

    # 2. Compile Kotlin sources (SpeechEngine.kt, MainActivity.kt, Sherpa)
    sherpa_src = os.path.join(APP_DIR, r"src\main\java\com\k2fsa\sherpa\onnx")
    src_kt = os.path.join(APP_DIR, r"src\main\java\in\itantra\mobile\SpeechEngine.kt")
    main_kt = os.path.join(APP_DIR, r"src\main\java\in\itantra\mobile\MainActivity.kt")
    service_kt = os.path.join(APP_DIR, r"src\main\java\in\itantra\mobile\MeshService.kt")
    boot_kt = os.path.join(APP_DIR, r"src\main\java\in\itantra\mobile\BootReceiver.kt")
    
    kt_sources = [src_kt, main_kt]
    if os.path.exists(service_kt):
        kt_sources.append(service_kt)
    if os.path.exists(boot_kt):
        kt_sources.append(boot_kt)
    if os.path.exists(sherpa_src):
        for f in os.listdir(sherpa_src):
            if f.endswith(".kt"):
                kt_sources.append(os.path.join(sherpa_src, f))

    kt_cp = f"{android_jar};{classes_dir};{existing_classes}"
    kt_args = " ".join([f'"{f}"' for f in kt_sources])
    kotlin_compiler_jar = os.path.join(USER_HOME, r".kotlinc\kotlinc\lib\kotlin-compiler.jar")
    run(f'"{JAVA_EXE}" -Xmx512m -XX:TieredStopAtLevel=1 -XX:CICompilerCount=2 -cp "{kotlin_compiler_jar}" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -cp "{kt_cp}" -d "{classes_dir}" {kt_args}', env=env)

    # 3. Dex the compiled classes with D8
    print("Dexing with D8...")
    class_files = []
    compiled_names = set()
    for root, dirs, files in os.walk(classes_dir):
        for f in files:
            if f.endswith(".class"):
                class_files.append(os.path.join(root, f))
                compiled_names.add(f.split("$")[0].replace(".class", ""))
    
    # Also include existing other mobile classes if present and not recompiled
    if os.path.exists(os.path.join(existing_classes, "in", "itantra", "mobile")):
        for f in os.listdir(os.path.join(existing_classes, "in", "itantra", "mobile")):
            if f.endswith(".class"):
                base_name = f.split("$")[0].replace(".class", "")
                if base_name not in compiled_names:
                    class_files.append(os.path.join(existing_classes, "in", "itantra", "mobile", f))
            
    argfile_path = os.path.join(SCRATCH, "d8_args.txt")
    with open(argfile_path, "w", encoding="utf-8") as f:
        for cf in class_files:
            f.write(cf.replace("\\", "/") + "\n")

    run(f'"{JAVA_EXE}" -cp "{BUNDLETOOL}" shadow.bundletool.com.android.tools.r8.D8 --lib "{android_jar}" --min-api 26 --output "{dex_dir}" "@{argfile_path}"')
    
    new_dex = os.path.join(dex_dir, "classes.dex")
    print(f"Generated new dex: {os.path.getsize(new_dex)} bytes")

    # 4. Assemble APK with compiled AndroidManifest.xml and assets
    base_apk = os.path.join(MOBILE_DIR, "app-signed.apk")
    unsigned_apk = os.path.join(MOBILE_DIR, "app-unsigned.apk")
    signed_apk = os.path.join(MOBILE_DIR, "app-signed.apk")
    new_html = os.path.join(APP_DIR, r"src\main\assets\index.html")

    # Compile AndroidManifest.xml using aapt2
    aapt2_exe = os.path.join(MOBILE_DIR, "aapt2.exe")
    manifest_xml = os.path.join(APP_DIR, r"src\main\AndroidManifest.xml")
    res_dir = os.path.join(APP_DIR, r"src\main\res")
    compiled_res = os.path.join(SCRATCH, "compiled_res.zip")
    manifest_apk = os.path.join(SCRATCH, "manifest_out.apk")

    print("Compiling AndroidManifest.xml and resources with aapt2...")
    run(f'"{aapt2_exe}" compile --dir "{res_dir}" -o "{compiled_res}"')
    run(f'"{aapt2_exe}" link -o "{manifest_apk}" -I "{android_jar}" --manifest "{manifest_xml}" "{compiled_res}" --min-sdk-version 26 --target-sdk-version 34 --auto-add-overlay')

    with zipfile.ZipFile(manifest_apk, "r") as mz:
        manifest_data = mz.read("AndroidManifest.xml")

    with open(new_dex, "rb") as f:
        dex_data = f.read()
    with open(new_html, "rb") as f:
        html_data = f.read()

    with zipfile.ZipFile(base_apk, "r") as zin, zipfile.ZipFile(unsigned_apk + ".tmp", "w", compression=zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            # Strip v1 signature entries
            if item.filename.startswith("META-INF/") and (item.filename.endswith(".SF") or item.filename.endswith(".RSA") or item.filename.endswith(".MF")):
                continue
            if item.filename == "AndroidManifest.xml":
                zout.writestr(item, manifest_data)
            elif item.filename == "classes6.dex":
                zout.writestr(item, dex_data)
            elif item.filename == "assets/index.html":
                zout.writestr(item, html_data)
            else:
                zout.writestr(item, zin.read(item.filename))
                
    if os.path.exists(unsigned_apk): os.remove(unsigned_apk)
    os.replace(unsigned_apk + ".tmp", unsigned_apk)
    print(f"Unsigned APK created: {os.path.getsize(unsigned_apk)} bytes")

    # 5. Sign APK
    keystore = os.path.join(MOBILE_DIR, "debug.keystore")
    signer_cp = f"{os.path.join(MOBILE_DIR, 'signer.jar')};{APKSIG_JAR}"
    run(f'"{JAVA_EXE}" -cp "{signer_cp}" SignerKt "{unsigned_apk}" "{signed_apk}" "{keystore}"')
    print("Signed APK successfully created!")

    # 6. Copy to web public
    for dest in [
        os.path.join(ROOT, r"apps\web\public\swar-debug.apk"),
        os.path.join(ROOT, r"apps\web\public\swar.apk"),
        os.path.join(ROOT, r"apps\web\public\linc-debug.apk"),
        os.path.join(ROOT, r"apps\web\public\linc.apk"),
        os.path.join(APP_DIR, r"build\outputs\apk\debug\app-debug.apk")
    ]:
        try:
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            shutil.copy(signed_apk, dest)
        except Exception as e:
            print(f"Copy note: {e}")

    # 7. Install to all connected devices
    adb_bin = shutil.which("adb") or (ADB if os.path.exists(ADB) else None) or (UNITY_ADB if os.path.exists(UNITY_ADB) else "adb")
    devices = []
    try:
        proc = subprocess.run([adb_bin, "devices"], capture_output=True, text=True)
        for line in proc.stdout.splitlines():
            parts = line.strip().split()
            if len(parts) >= 2 and parts[1] == "device":
                devices.append(parts[0])
    except Exception as e:
        print("Could not query adb devices:", e)

    if not devices:
        print("No connected ADB devices found. APK is ready at mobile/app-signed.apk")
        return

    for dev in devices:
        print(f"Installing and launching on device {dev}...")
        try:
            run(f'"{adb_bin}" -s {dev} install -r -d "{signed_apk}"')
            for perm in [
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.BLUETOOTH_ADVERTISE",
                "android.permission.NEARBY_WIFI_DEVICES",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.RECORD_AUDIO",
                "android.permission.POST_NOTIFICATIONS"
            ]:
                subprocess.run([adb_bin, "-s", dev, "shell", "pm", "grant", "in.itantra.mobile", perm], capture_output=True)
            run(f'"{adb_bin}" -s {dev} shell am start -n in.itantra.mobile/.MainActivity')
            print(f"SUCCESS on {dev}!")
        except Exception as e:
            print(f"Failed to install on {dev}: {e}")

if __name__ == "__main__":
    build()
