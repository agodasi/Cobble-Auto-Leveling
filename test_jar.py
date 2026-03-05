import zipfile

jar_path = 'e:/program files/auto-minecraft/pokemon/libs/baritone-unoptimized.jar'

with zipfile.ZipFile(jar_path, 'r') as zf:
    content = zf.read('META-INF/mods.toml').decode('utf-8')
    for line in content.split('\n'):
        if 'modId=' in line:
            print(line.strip())
