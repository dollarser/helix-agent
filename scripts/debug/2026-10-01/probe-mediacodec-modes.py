#!/usr/bin/env python3
"""Diagnose documented MediaCodec modes on the same bounded emulator fixture; retains every failed observation."""
import argparse
import importlib.util
import json
from pathlib import Path

spec=importlib.util.spec_from_file_location('media_probe',Path(__file__).with_name('probe-ffmpeg-plus-android.py'))
probe=importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


def main(serial):
    p=probe.Probe(serial,'extended')
    p.setup()
    for codec,asynchronous in [('h264',False),('h264',True),('hevc',False),('hevc',True)]:
        label=f'{codec}-'+('async' if asynchronous else 'sync')+'-explicit-extradata'
        def check(codec=codec,asynchronous=asynchronous,label=label):
            output=label+'.mp4'
            p.ff(label,'-y','-f','rawvideo','-pixel_format','yuv420p','-video_size','320x240','-framerate','15',
                 '-i','input.yuv','-an','-c:v',codec+'_mediacodec','-ndk_codec','1','-ndk_async',str(int(asynchronous)),
                 '-flags','-global_header','-bsf:v','extract_extradata','-b:v','500k','-g','15','-pix_fmt','yuv420p',output)
            info=p.info(label+'-probe',output)
            videos=[x for x in info['streams'] if x.get('codec_type')=='video']
            assert len(videos)==1,f'Expected one video stream, got {info}'
            video=videos[0]
            assert int(video.get('nb_read_frames',0))==30,f'Expected 30 decoded frames: {video}'
            assert video['codec_name']==codec
            return {'codec':codec,'async':asynchronous,'decoded_frames':30,'mode':'explicit extract_extradata, no global header'}
        p.record(label,check)
    result={'serial':serial,'tests':p.results,'remote':p.remote,'work':str(p.work.relative_to(probe.ROOT))}
    (probe.OUT/'mediacodec-mode-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'passed':sum(x['status']=='passed' for x in p.results),'failed':sum(x['status']=='failed' for x in p.results)}))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',required=True)
    main(parser.parse_args().serial)
