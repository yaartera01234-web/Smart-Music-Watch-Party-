#!/usr/bin/env python3
"""Byte-exact Synkplay 0.23.0 native engine + source-identical rebuilt JNI.
Never modifies dex/resources/app logic. Output is UNSIGNED; align/re-sign afterwards.
"""
import argparse, hashlib, json, pathlib, re, shutil, subprocess, tempfile, urllib.request, zipfile
HERE = pathlib.Path(__file__).resolve().parent
M = json.loads((HERE / 'manifest.json').read_text())
PREFIX = 'lib/arm64-v8a/'
BRIDGE = 'libmpvkt_jni.so'
SYSTEM = {'libc.so','libm.so','libdl.so','libz.so','libandroid.so','libmediandk.so',
          'libOpenSLES.so','libEGL.so','libGLESv2.so','liblog.so','libjnigraphics.so'}
def digest(b): return hashlib.sha256(b).hexdigest()
def checked_source():
    for name, meta in M['source_files'].items():
        assert digest((HERE/'vendor'/name).read_bytes()) == meta['sha256'], name

def prepare(work, reference=None):
    checked_source(); work.mkdir(parents=True, exist_ok=True)
    apk = pathlib.Path(reference) if reference else work/'reference.apk'
    if not apk.exists():
        urllib.request.urlretrieve(M['synk_apk_url'], apk)
    assert digest(apk.read_bytes()) == M['synk_apk_sha256'], 'Reference APK checksum mismatch'
    dest = work/'native';dest.mkdir(exist_ok=True)
    with zipfile.ZipFile(apk) as z:
        for name, sha in M['native_libraries'].items():
            b=z.read(PREFIX+name);assert digest(b)==sha, name
            (dest/name).write_bytes(b)
    print('PASS: nine exact reference native libraries and all bridge sources verified')

def build(work, ndk):
    checked_source();ndk=pathlib.Path(ndk)
    assert ('Pkg.Revision = '+M['ndk']) in (ndk/'source.properties').read_text(), 'Unexpected NDK'
    tool=ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin'
    src=HERE/'vendor'
    cmd=[str(tool/'aarch64-linux-android26-clang++'),'-std=c++20','-shared','-fPIC','-O2',
         '-Wall','-Wextra','-Werror','-fvisibility=hidden','-fvisibility-inlines-hidden',
         '-nostdlib++','-I'+str(src/'include')]
    cmd += [str(src/'jni'/n) for n in ['mpvkt.cpp','node_codec.cpp','stream_cb.cpp','utf8.cpp']]
    cmd += ['-L'+str(work/'native'),'-lmpv','-lavcodec','-l:libc++_shared.so',
            '-Wl,--no-undefined','-Wl,-soname,libmpvkt_jni.so',
            '-Wl,-z,max-page-size=16384','-Wl,-z,common-page-size=16384','-o',str(work/BRIDGE)]
    subprocess.run(cmd,check=True)
    subprocess.run([str(tool/'llvm-strip'),'--strip-unneeded',str(work/BRIDGE)],check=True)
    versions=subprocess.check_output(['readelf','--version-info',str(work/BRIDGE)],text=True)
    assert 'LIBAVCODEC_62' in versions and 'LIBAVCODEC_63' not in versions
    print('PASS: source-identical JNI re-linked against LIBAVCODEC_62; no engine rebuilt')

def symbols(p):
    text=subprocess.check_output(['readelf','--dyn-syms','-W',str(p)],text=True)
    imports=set();exports=set()
    for line in text.splitlines():
        v=line.split()
        if len(v)<8 or not v[0].rstrip(':').isdigit():continue
        name=v[7]
        if v[6]=='UND' and v[4]!='WEAK':imports.add(name)
        elif v[6]!='UND' and v[4] in ('GLOBAL','WEAK'):
            exports.add(name.replace('@@','@'))
            if '@@' in name or '@' not in name:exports.add(name.split('@')[0])
    return imports,exports

def link_check(work, base):
    """Check versioned native symbol providers, not only unversioned function names."""
    with tempfile.TemporaryDirectory() as td, zipfile.ZipFile(base) as z:
        td=pathlib.Path(td);original_exports=set();new_exports=set();retained=[]
        for name in M['native_libraries']:
            p=td/name;p.write_bytes(z.read(PREFIX+name));original_exports|=symbols(p)[1]
            new_exports|=symbols(work/'native'/name)[1]
        orig_names={n.split('@')[0] for n in original_exports}
        for entry in z.namelist():
            if entry.startswith(PREFIX) and entry.endswith('.so'):
                name=entry[len(PREFIX):]
                if name in M['native_libraries']:continue
                p=td/name;p.write_bytes(z.read(entry))
                # libpython.zip.so is an archive, not an ELF library.
                if not p.read_bytes().startswith(b'\x7fELF'):continue
                retained.append((name,p))
        original_bridge=next(p for name,p in retained if name==BRIDGE)
        original_imports,_=symbols(original_bridge)
        assert any(n.endswith('@LIBAVCODEC_63') for n in original_imports), 'Unexpected original JNI ABI'
        assert any(n not in new_exports for n in original_imports if n.split('@')[0] in orig_names), 'Negative ABI control failed'
        for name,p in retained:
            if name==BRIDGE:p=work/BRIDGE
            imports,_=symbols(p)
            required={n for n in imports if n.split('@')[0] in orig_names}
            missing=required-new_exports
            assert not missing,(name,sorted(missing))
        # Java native methods must not disappear/change name during the bridge rebuild.
        _,old= symbols(original_bridge);_,new=symbols(work/BRIDGE)
        jni=lambda names:{n for n in names if n.startswith('Java_') or n=='JNI_OnLoad'}
        assert jni(old)==jni(new),'JNI exports changed'
        for p in list((work/'native').glob('*.so'))+[work/BRIDGE]:
            needs=re.findall(r'\(NEEDED\).*?\[(.*?)\]',subprocess.check_output(['readelf','-d',str(p)],text=True))
            assert not (set(needs)-set(M['native_libraries'])-SYSTEM),(p.name,needs)
    print('PASS: versioned JNI/native symbol checks, JNI method parity, dependency closure; old JNI correctly rejected')

def signature_entry(name):
    return name=='META-INF/MANIFEST.MF' or bool(re.fullmatch(r'META-INF/[^/]+\.(SF|RSA|DSA|EC)',name,re.I))

def swap(work, base, output):
    link_check(work,base)
    with zipfile.ZipFile(base) as src,zipfile.ZipFile(output,'w') as out:
        assert len(src.namelist())==len(set(src.namelist())), 'Duplicate APK entries'
        for info in src.infolist():
            if signature_entry(info.filename):continue
            name=info.filename[len(PREFIX):] if info.filename.startswith(PREFIX) else ''
            if name in M['native_libraries']:data=(work/'native'/name).read_bytes()
            elif name==BRIDGE:data=(work/BRIDGE).read_bytes()
            else:data=src.read(info.filename)
            out.writestr(info,data)
    verify(work,base,output)
    print('UNSIGNED APK ready: align and re-sign before installation')

def verify(work, base, candidate):
    with zipfile.ZipFile(base) as src,zipfile.ZipFile(candidate) as out:
        expected={x for x in src.namelist() if not signature_entry(x)}
        actual={x for x in out.namelist() if not signature_entry(x)}
        assert actual==expected,'APK entry set changed'
        changed=[]
        for name in sorted(actual):
            data=out.read(name)
            lib=name[len(PREFIX):] if name.startswith(PREFIX) else ''
            if lib in M['native_libraries']:
                assert digest(data)==M['native_libraries'][lib],lib;changed.append(lib)
            elif lib==BRIDGE:
                assert data==(work/BRIDGE).read_bytes();changed.append(lib)
            else:assert data==src.read(name),'Unrelated APK entry changed: '+name
        assert len(changed)==10
        assert b'mpv v0.41.0-252-gc401ef9c3\0' in out.read(PREFIX+'libmpv.so')
        assert b'FFmpeg version N-123143-g0540b42657\0' in out.read(PREFIX+'libavcodec.so')
    print('PASS: exact Synk MPV/FFmpeg/C++ hashes; only nine native libs + JNI differ; all dex/assets/resources unchanged')

if __name__=='__main__':
    a=argparse.ArgumentParser();a.add_argument('action',choices=['prepare','build','check','swap','verify']);a.add_argument('--work',type=pathlib.Path,required=True);a.add_argument('--reference');a.add_argument('--ndk');a.add_argument('--base');a.add_argument('--output');x=a.parse_args();x.work=x.work.resolve()
    if x.action=='prepare':prepare(x.work,x.reference)
    elif x.action=='build':build(x.work,x.ndk)
    elif x.action=='check':link_check(x.work,x.base)
    elif x.action=='swap':swap(x.work,x.base,x.output)
    else:verify(x.work,x.base,x.output)
