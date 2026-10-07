"""Linux MPV IPC smoke test; NOT Android UI/gesture/total-RAM verification.
Requires mpv and ffmpeg. Fixtures/process/socket are temporary and removed.
"""
import json, pathlib, socket, subprocess, tempfile, time
with tempfile.TemporaryDirectory(prefix='wp-test4-') as folder:
    d=pathlib.Path(folder);video=d/'video.mp4';audio=d/'audio.m4a';ipc=d/'ipc'
    subprocess.run(['ffmpeg','-loglevel','error','-f','lavfi','-i','testsrc2=size=320x180:rate=24','-t','12','-c:v','libx264','-pix_fmt','yuv420p',str(video)],check=True)
    subprocess.run(['ffmpeg','-loglevel','error','-f','lavfi','-i','sine=frequency=440','-t','12','-c:a','aac',str(audio)],check=True)
    proc=subprocess.Popen(['mpv','--no-config','--idle=yes','--vo=null','--ao=null','--pause=yes','--cache=yes','--cache-secs=86400','--demuxer-readahead-secs=86400','--demuxer-max-bytes=104857600','--demuxer-max-back-bytes=8388608','--input-ipc-server='+str(ipc)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    try:
        until=time.monotonic()+5
        while not ipc.exists() and time.monotonic()<until: time.sleep(.02)
        sock=socket.socket(socket.AF_UNIX);sock.settimeout(5);sock.connect(str(ipc));stream=sock.makefile('rwb',buffering=0);seq=0
        def cmd(*args):
            global seq
            seq+=1;stream.write((json.dumps({'command':args,'request_id':seq})+'\n').encode())
            while True:
                msg=json.loads(stream.readline())
                if msg.get('request_id')==seq:
                    assert msg.get('error')=='success',msg
                    return msg.get('data')
        def wait_loaded():
            end=time.monotonic()+5
            while time.monotonic()<end:
                try:
                    if cmd('get_property','duration')>0:return
                except AssertionError:pass
                time.sleep(.05)
            raise AssertionError('load timeout')
        cmd('loadfile',str(video),'replace',-1,'start=3,demuxer-max-bytes=104857600,demuxer-max-back-bytes=8388608');wait_loaded()
        assert cmd('get_property','options/demuxer-max-bytes')==104857600
        assert cmd('get_property','options/demuxer-readahead-secs')==86400
        assert cmd('get_property','options/cache-secs')==86400
        pos=cmd('get_property','time-pos')
        for ratio,pan in [(-1,0),(1.777778,0),(1.6,0),(1.333333,0),(2.35,0),(-1,1),(-1,0)]:
            cmd('set_property','video-aspect-override',ratio);cmd('set_property','panscan',pan)
            assert abs(cmd('get_property','video-aspect-override')-ratio)<.00001
            assert cmd('get_property','panscan')==pan
            assert abs(cmd('get_property','time-pos')-pos)<.05
        print('PASS actual MPV: six aspect property modes and Original reset; no timeline change')
        for speed in [.995,.95,1,1.005,1]:
            cmd('set_property','speed',speed);assert cmd('get_property','speed')==speed
        print('PASS actual MPV: sync speeds 0.995 / 0.95 / 1.005 / restore 1.0')
        quoted='%'+str(len(str(audio).encode()))+'%'+str(audio)
        cmd('loadfile',str(video),'replace',-1,'start=3,demuxer-max-bytes=52428800,demuxer-max-back-bytes=4194304,audio-files-clr=,audio-files-append='+quoted)
        time.sleep(.4);wait_loaded()
        assert cmd('get_property','options/demuxer-max-bytes')==52428800
        assert cmd('get_property','options/demuxer-max-back-bytes')==4194304
        assert any(t['type']=='audio' for t in cmd('get_property','track-list'))
        print('PASS actual MPV: 100MiB single-stream option / 50MiB per-demuxer split with separate audio, 24h read-ahead accepted')
        print('Not a measurement of aggregate Android RAM, HTTP prefetch completion, or audible pitch.')
    finally:
        proc.terminate()
        try:proc.wait(timeout=5)
        except subprocess.TimeoutExpired:proc.kill();proc.wait()
