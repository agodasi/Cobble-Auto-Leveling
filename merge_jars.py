import zipfile
import shutil
import os

api_jar = 'e:/program files/auto-minecraft/pokemon/libs/baritone-api-forge-1.10.3.jar'
standalone_jar = 'e:/program files/auto-minecraft/pokemon/libs/baritone-standalone-forge-1.10.3.jar'
out_jar = 'e:/program files/auto-minecraft/pokemon/libs/baritone-full.jar'

# We will use standalone's mods.toml as the base.
# We will copy everything from API jar except its mods.toml.
# We will copy everything from Standalone jar.

with zipfile.ZipFile(out_jar, 'w', zipfile.ZIP_DEFLATED) as zout:
    # 1. API Jar
    with zipfile.ZipFile(api_jar, 'r') as zin:
        for item in zin.infolist():
            if item.filename != 'META-INF/mods.toml':
                zout.writestr(item, zin.read(item.filename))
    
    # 2. Standalone Jar
    with zipfile.ZipFile(standalone_jar, 'r') as zin:
        for item in zin.infolist():
            # Don't overwrite classes already copied from API (though standalone had almost none)
            try:
                zout.getinfo(item.filename)
                # print(f'Skipping duplicate: {item.filename}')
                continue
            except KeyError:
                zout.writestr(item, zin.read(item.filename))

print('Merged into baritone-full.jar')
