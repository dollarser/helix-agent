#!/usr/bin/env python3
"""Bounded synthetic media checks on one explicitly selected emulator. No user app/data or account access."""
from __future__ import annotations
import argparse
import array
import hashlib
import json
import math
import os
from pathlib import Path
import shlex
import subprocess
import sys
import time
import uuid

ROOT=Path(__file__).resolve().parents[3]
OUT=ROOT/'build/ffmpeg-plus-size-2026-10-01'
ADB=Path(os.environ['ANDROID_HOME'])/'platform-tools/adb'

class Probe:
    def __init__(self, serial, profile):
        if not serial.startswith('emulator-'):
            raise ValueError('This experiment is authorized for an emulator, not a personal device')
        self.serial=serial
        self.profile=profile
        self.tag='helix-ffmpeg-plus-'+uuid.uuid4().hex[:12]
        self.remote='/data/local/tmp/'+self.tag
        self.work=OUT/'android-probe'/self.tag
        self.work.mkdir(parents=True)
        self.results=[]
        self.counter=0

    def adb(self,*args,timeout=50):
        return subprocess.run([str(ADB),'-s',self.serial,*map(str,args)],capture_output=True,timeout=timeout)

    def shell(self,args,timeout=50):
        result=self.adb('shell',shlex.join([str(v) for v in args]),timeout=timeout)
        return result

    def command(self,name,binary,*args,check=True):
        self.counter+=1
        argv=['timeout','-k','3','30','env','LD_LIBRARY_PATH='+self.remote,self.remote+'/'+binary,*args]
        command='cd '+shlex.quote(self.remote)+' && '+shlex.join([str(v) for v in argv])
        result=self.adb('shell',command,timeout=40)
        stdout=result.stdout.decode('utf-8',errors='replace')
        stderr=result.stderr.decode('utf-8',errors='replace')
        log=self.work/f'{self.counter:03d}-{name}'
        log.with_suffix('.stdout.log').write_text(stdout)
        log.with_suffix('.stderr.log').write_text(stderr)
        if check and result.returncode:
            raise AssertionError(f'{name} exited {result.returncode}: {(stdout+stderr)[-2500:]}')
        return stdout+stderr

    def ff(self,name,*args,check=True):
        return self.command(name,'ffmpeg','-hide_banner','-nostdin','-loglevel','info',*args,check=check)

    def info(self,name,file):
        return json.loads(self.command(name,'ffprobe','-v','error','-count_frames','-show_streams','-show_format','-of','json',file))

    def push(self,file):
        result=self.adb('push',file,self.remote+'/'+file.name)
        if result.returncode: raise RuntimeError(result.stderr.decode())

    def pull_bytes(self,file):
        target=self.work/file
        result=self.adb('pull',self.remote+'/'+file,target)
        if result.returncode: raise RuntimeError(result.stderr.decode())
        return target.read_bytes()

    def record(self,name,fn):
        started=time.monotonic()
        try:
            details=fn() or {}
            result={'name':name,'status':'passed','details':details}
        except Exception as error:
            result={'name':name,'status':'failed','error':f'{type(error).__name__}: {error}'}
        result['seconds']=round(time.monotonic()-started,3)
        self.results.append(result)
        print(json.dumps(result,ensure_ascii=False),flush=True)
        self.save()

    def save(self):
        result={'serial':self.serial,'profile':self.profile,'remote':self.remote,'tests':self.results,
                'passed':sum(x['status']=='passed' for x in self.results),'failed':sum(x['status']=='failed' for x in self.results),
                'scope':'ARM64 Android emulator native CLI/MediaCodec, not an integrated Helix App or physical-device performance test'}
        (self.work/'results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
        (OUT/f'android-{self.profile}-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')

    def setup(self):
        assert self.shell(['getprop','ro.product.cpu.abi']).stdout.strip()==b'arm64-v8a'
        metadata={key:self.shell(args).stdout.decode().strip() for key,args in {
            'api':['getprop','ro.build.version.sdk'],'fingerprint':['getprop','ro.build.fingerprint'],
            'page_size':['getconf','PAGESIZE']}.items()}
        (self.work/'device.json').write_text(json.dumps(metadata,indent=2)+'\n')
        result=self.shell(['mkdir',self.remote])
        if result.returncode: raise RuntimeError('Could not create unique test directory')
        size=json.loads((OUT/f'size-{self.profile}-arm64-v8a.json').read_text())
        stage=OUT/'payload'/self.profile/'arm64-v8a'
        for row in size['files']:
            assert hashlib.sha256((stage/row['file']).read_bytes()).hexdigest()==row['sha256']
            self.push(stage/row['file'])
        self.shell(['chmod','700',self.remote+'/ffmpeg',self.remote+'/ffprobe'])
        width,height=320,240
        raw=bytearray()
        for frame in range(30):
            raw.extend(bytes([64+frame])* (width*height))
            raw.extend(bytes([96])* (width*height//4))
            raw.extend(bytes([160])* (width*height//4))
        (self.work/'input.yuv').write_bytes(raw)
        audio=array.array('h',(0 if i<24000 or i>=72000 else int(4000*math.sin(2*math.pi*440*i/48000)) for i in range(96000)))
        if sys.byteorder!='little':audio.byteswap()
        (self.work/'input.pcm').write_bytes(audio.tobytes())
        (self.work/'list.txt').write_text("file 'input.mp4'\nfile 'input.mp4'\n")
        (self.work/'caption.txt').write_text('Helix 中文字幕测试',encoding='utf-8')
        (self.work/'caption.srt').write_text('1\n00:00:00,000 --> 00:00:01,000\nHelix 中文字幕测试\n',encoding='utf-8')
        for name in ['input.yuv','input.pcm','list.txt','caption.txt','caption.srt']:
            self.push(self.work/name)

    def seed(self):
        self.ff('seed','-y','-f','rawvideo','-pixel_format','yuv420p','-video_size','320x240','-framerate','15','-i','input.yuv',
                '-f','s16le','-ar','48000','-ac','1','-i','input.pcm','-c:v','mpeg4','-g','15','-q:v','4','-c:a','aac','-shortest','input.mp4')
        info=self.info('seed-probe','input.mp4')
        assert {x['codec_name'] for x in info['streams']}=={'mpeg4','aac'}
        assert 1.9<float(info['format']['duration'])<2.2
        return {'duration':info['format']['duration']}

    def mediacodec(self,codec):
        log=self.ff(codec+'-encode','-y','-i','input.mp4','-an','-c:v',codec+'_mediacodec','-ndk_codec','1',
                    '-ndk_async','1','-flags','-global_header','-bsf:v','extract_extradata',
                    '-b:v','500k','-g','15','-pix_fmt','yuv420p',codec+'.mp4')
        info=self.info(codec+'-probe',codec+'.mp4')
        videos=[x for x in info['streams'] if x['codec_type']=='video']
        assert len(videos)==1,f'Expected one video stream; zero-exit empty outputs are not success: {info}'
        video=videos[0]
        assert video['codec_name']==codec and video['width']==320 and video['height']==240
        assert int(video['nb_read_frames'])==30
        return {'codec':codec,'frames':30,'async':True,'extradata':'explicit bitstream filter',
                'implementation_note':'MediaCodec implementation is device-selected; no hardware-speed claim'}

    def concat(self):
        self.ff('concat','-y','-f','concat','-safe','1','-i','list.txt','-map','0','-c','copy','concat.mp4')
        info=self.info('concat-probe','concat.mp4')
        video=next(x for x in info['streams'] if x['codec_type']=='video')
        assert int(video['nb_read_frames'])==60
        return {'frames':60,'duration':info['format']['duration']}

    def segment(self):
        self.ff('segment','-y','-i','input.mp4','-c','copy','-f','segment','-segment_time','1','-reset_timestamps','1','part%02d.mp4')
        a=self.info('segment-0','part00.mp4');b=self.info('segment-1','part01.mp4')
        frames=sum(int(next(x for x in info['streams'] if x['codec_type']=='video')['nb_read_frames']) for info in [a,b])
        assert frames==30
        return {'segments':2,'decoded_frames_total':frames}

    def fade(self):
        self.ff('fade','-y','-i','input.mp4','-an','-vf','fade=t=in:st=0:d=0.5','-c:v','rawvideo','-pix_fmt','yuv420p','-f','rawvideo','fade.yuv')
        data=self.pull_bytes('fade.yuv');pixels=320*240;stride=pixels*3//2
        assert len(data)==stride*30
        assert sum(data[:pixels])/pixels<18
        assert sum(data[15*stride:15*stride+pixels])/pixels>40
        return {'frames':30,'first_frame_black':True,'later_frame_visible':True}

    def silence(self):
        log=self.ff('silence-detect','-f','s16le','-ar','48000','-ac','1','-i','input.pcm','-af','silencedetect=noise=-50dB:d=0.2','-f','null','-')
        assert 'silence_start: 0' in log and 'silence_end: 0.5' in log
        self.ff('silence-remove','-y','-f','s16le','-ar','48000','-ac','1','-i','input.pcm',
                '-af','silenceremove=start_periods=1:start_duration=0.1:start_threshold=-50dB:stop_periods=-1:stop_duration=0.2:stop_threshold=-50dB',
                '-c:a','pcm_s16le','trimmed.wav')
        duration=float(self.info('silence-probe','trimmed.wav')['format']['duration'])
        assert 0.8<duration<1.4
        return {'trimmed_seconds':duration}

    def loudness(self):
        log=self.ff('loudnorm','-y','-f','s16le','-ar','48000','-ac','1','-i','input.pcm',
                    '-af','loudnorm=I=-16:TP=-1.5:LRA=11:print_format=json','-ar','48000','-c:a','pcm_s16le','normalized.wav')
        begin=log.rfind('{');end=log.rfind('}')
        values=json.loads(log[begin:end+1]);assert math.isfinite(float(values['output_i']))
        assert abs(float(values['output_i'])+16)<2
        assert self.info('loudnorm-probe','normalized.wav')['streams'][0]['sample_rate']=='48000'
        return values

    def encode_audio(self,codec,extension,expected):
        self.ff(codec+'-encode','-y','-f','s16le','-ar','48000','-ac','1','-i','input.pcm','-c:a',codec,'-b:a','96k','encoded.'+extension)
        stream=self.info(codec+'-probe','encoded.'+extension)['streams'][0]
        assert stream['codec_name']==expected
        self.ff(codec+'-decode','-y','-i','encoded.'+extension,'-c:a','pcm_s16le','-f','s16le',expected+'.pcm')
        assert len(self.pull_bytes(expected+'.pcm'))>=180000
        return {'codec':expected,'roundtrip_decoded':True}

    def webp(self):
        self.ff('base-frame','-y','-i','input.mp4','-frames:v','1','baseline.png')
        self.ff('webp-lossless','-y','-i','baseline.png','-c:v','libwebp','-lossless','1','-pix_fmt','bgra','output.webp')
        assert self.info('webp-probe','output.webp')['streams'][0]['codec_name']=='webp'
        for file,output in [('baseline.png','base.rgba'),('output.webp','webp.rgba')]:
            self.ff('rgba-'+output,'-y','-i',file,'-frames:v','1','-pix_fmt','rgba','-f','rawvideo',output)
        assert self.pull_bytes('base.rgba')==self.pull_bytes('webp.rgba')
        return {'lossless_pixel_match':True}

    def animated_webp(self):
        import struct
        # Use distinct raw frames and lossless encoding: lossy near-identical frames may legitimately coalesce.
        self.ff('animated-webp','-y','-f','rawvideo','-pixel_format','yuv420p','-video_size','320x240','-framerate','15',
                '-i','input.yuv','-an','-frames:v','10','-c:v','libwebp_anim','-lossless','1','-pix_fmt','bgra','-loop','0','animation.webp')
        data=self.pull_bytes('animation.webp')
        assert data[:4]==b'RIFF' and data[8:12]==b'WEBP'
        assert int.from_bytes(data[4:8],'little')+8==len(data)
        offset=12;tags=[];duration_ms=0
        while offset<len(data):
            tag,size=struct.unpack_from('<4sI',data,offset)
            assert offset+8+size<=len(data)
            tags.append(tag)
            if tag==b'ANMF':
                assert size>=16
                duration_ms+=int.from_bytes(data[offset+20:offset+23],'little')
            offset+=8+size+(size%2)
        assert offset==len(data) and b'ANIM' in tags and tags.count(b'ANMF')==10,repr(tags)
        assert 650<=duration_ms<=690,f'Expected 10 frames at 15 fps; got {duration_ms} ms'
        return {'animation_frames':10,'duration_ms':duration_ms,'container_lengths_verified':True}

    def transitions(self):
        self.ff('xfade','-y','-i','input.mp4','-i','input.mp4','-filter_complex',
                '[0:v][1:v]xfade=transition=fade:duration=0.5:offset=1.5[v]',
                '-map','[v]','-an','-c:v','mpeg4','-q:v','4','transition.mp4')
        info=self.info('xfade-probe','transition.mp4')
        assert 3.4<=float(info['format']['duration'])<=3.7
        video=next(x for x in info['streams'] if x['codec_type']=='video')
        assert 50<=int(video['nb_read_frames'])<=56
        self.ff('acrossfade','-y','-i','input.mp4','-i','input.mp4',
                '-filter_complex','[0:a][1:a]acrossfade=d=0.5[a]',
                '-map','[a]','-c:a','pcm_s16le','cross.wav')
        seconds=float(self.info('acrossfade-probe','cross.wav')['format']['duration'])
        assert 3.4<seconds<3.7
        return {'video_frames':video['nb_read_frames'],'audio_seconds':seconds}

    def sound_processing(self):
        self.ff('sound-processing','-y','-i','input.mp4','-vn',
                '-af','dynaudnorm,equalizer=f=1000:t=q:w=1:g=3,acompressor,alimiter',
                '-ar','48000','-c:a','pcm_s16le','processed.wav')
        info=self.info('sound-processing-probe','processed.wav')
        assert 1.9<float(info['format']['duration'])<2.2
        assert info['streams'][0]['codec_name']=='pcm_s16le'
        return {'dynamic_normalizer_equalizer_compressor_limiter':True}

    def failure_boundaries(self):
        path=self.work/'bad.mp4';path.write_bytes(b'not-a-media-file');self.push(path)
        bad=self.ff('malformed-rejection','-i','bad.mp4','-f','null','-',check=False)
        assert 'Invalid data found when processing input' in bad
        before=self.pull_bytes('input.mp4')
        refused=self.ff('overwrite-refusal','-n','-i','input.mp4','-c','copy','input.mp4',check=False)
        assert 'already exists' in refused or 'same as Input' in refused
        assert self.pull_bytes('input.mp4')==before
        protocols=self.command('protocols','ffmpeg','-hide_banner','-protocols')
        assert 'http' not in protocols and 'tcp' not in protocols and 'file' in protocols
        return {'malformed_input_rejected':True,'input_not_overwritten':True,'network_protocols_absent':True}

    def text(self):
        font='/system/fonts/NotoSansCJK-Regular.ttc'
        assert self.shell(['test','-r',font]).returncode==0
        self.ff('drawtext','-y','-i','input.mp4','-vf',f'drawtext=fontfile={font}:textfile=caption.txt:fontsize=24:fontcolor=white:x=10:y=20',
                '-frames:v','1','text.png')
        self.ff('plain','-y','-i','input.mp4','-frames:v','1','plain.png')
        assert self.pull_bytes('text.png')!=self.pull_bytes('plain.png')
        return {'font':font,'system_font_not_bundled':True,'pixel_output_changed':True}

    def subtitles(self):
        self.ff('srt-to-ass','-y','-i','caption.srt','caption.ass')
        caption=self.pull_bytes('caption.ass').decode('utf-8-sig')
        assert ',Arial,' in caption
        (self.work/'caption.ass').write_text(caption.replace(',Arial,',',Noto Sans CJK SC,'),encoding='utf-8')
        self.push(self.work/'caption.ass')
        for name,filter_value in [('srt','subtitles=caption.srt:fontsdir=/system/fonts:force_style=FontName=Noto Sans CJK SC'),
                                  ('ass','ass=caption.ass:fontsdir=/system/fonts')]:
            log=self.ff(name+'-burn','-y','-i','input.mp4','-vf',filter_value,
                        '-frames:v','1',name+'.png')
            assert 'Glyph 0x' not in log or 'not found' not in log
            assert self.pull_bytes(name+'.png')!=self.pull_bytes('plain.png')
        self.ff('soft-subtitles','-y','-i','input.mp4','-i','caption.srt','-map','0','-map','1:0','-c','copy','-c:s','mov_text','soft.mp4')
        assert 'mov_text' in {x['codec_name'] for x in self.info('soft-probe','soft.mp4')['streams']}
        return {'srt_burned':True,'ass_burned':True,'mov_text_muxed':True}

    def run(self):
        self.setup()
        self.record('mpeg4_aac_seed',self.seed)
        self.record('h264_mediacodec_encode_decode',lambda:self.mediacodec('h264'))
        self.record('hevc_mediacodec_encode_decode',lambda:self.mediacodec('hevc'))
        self.record('concat_demuxer_stream_copy',self.concat)
        self.record('segment_muxer',self.segment)
        self.record('video_fade_pixels',self.fade)
        self.record('silence_detection_and_removal',self.silence)
        self.record('loudness_normalization',self.loudness)
        self.record('video_and_audio_crossfade',self.transitions)
        self.record('dynamic_normalization_equalizer_compressor_limiter',self.sound_processing)
        self.record('malformed_overwrite_and_network_boundaries',self.failure_boundaries)
        if self.profile=='extended':
            self.record('mp3_encode_decode',lambda:self.encode_audio('libmp3lame','mp3','mp3'))
            self.record('opus_encode_decode',lambda:self.encode_audio('libopus','opus','opus'))
            self.record('webp_lossless_encode_decode',self.webp)
            self.record('animated_webp',self.animated_webp)
            self.record('chinese_drawtext',self.text)
            self.record('srt_ass_and_soft_subtitles',self.subtitles)
        # Unique synthetic directory retained for diagnostic reproduction; no persistent processes are started.
        self.save()
        return 1 if any(x['status']=='failed' for x in self.results) else 0

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',required=True)
    parser.add_argument('--profile',choices=['common','extended'],default='extended')
    args=parser.parse_args()
    raise SystemExit(Probe(args.serial,args.profile).run())
