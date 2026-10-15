#!/usr/bin/env python3
"""Functional checks using the freshly compiled HOST companion, never Android or real user media."""
from __future__ import annotations
import argparse
import array
import json
import math
from pathlib import Path
import shutil
import struct
import sys
import time
import unittest
import uuid
import zlib

from artifacts import verify_build
from contract import color_facts, guard_color, verify_result
from kit import HERE, OUT, atomic_json, digest, identity, require, run
import test_contract

PROFILE='ready'
WORK=None
BIN=None
COUNTER=0


def command(executable, args, label='command', expect=(0,)):
    global COUNTER
    COUNTER+=1
    log=WORK/f'{COUNTER:04}-{label}.log'
    run([executable]+list(args),log=log,cwd=WORK,timeout=90,expect=expect)
    return log.read_text(errors='replace')


def ff(*args, label='ffmpeg', expect=(0,)):
    return command(BIN/'ffmpeg',['-hide_banner','-nostdin','-v','error']+list(args),label,expect)


def probe(file, frames=False):
    args=['-v','error','-count_frames','-show_streams','-show_format','-of','json']
    if frames:
        args+=['-show_frames']
    return json.loads(command(BIN/'ffprobe',args+[file],'probe'))


def rgba(file, name, extra=()):
    ff('-y','-i',file,*extra,'-fps_mode','passthrough','-pix_fmt','rgba','-f','rawvideo',name)
    return (WORK/name).read_bytes()


def png_file(file,w,h,pixels):
    def chunk(kind,data):
        return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data))
    rows=b''.join(b'\0'+pixels[i*w*4:(i+1)*w*4] for i in range(h))
    (WORK/file).write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(rows))+chunk(b'IEND',b''))


def fixture_full(args,label):
    full=shutil.which('ffmpeg')
    require(full is not None,'Full host FFmpeg is needed ONLY to generate independent codec fixtures')
    return command(full,['-hide_banner','-v','error','-nostdin','-y']+list(args),'fixture-'+label)


class MediaTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if (WORK/'input.mp4').exists():
            return
        raw=bytearray()
        for frame in range(8):
            raw+=bytes(16+(x+y+frame*20)%210 for y in range(48) for x in range(64))
            raw+=bytes([96+frame])*768+bytes([160-frame])*768
        (WORK/'input.yuv').write_bytes(raw)
        samples=array.array('h',(0 if i<24000 else int(10000*math.sin(2*math.pi*440*i/48000)) for i in range(96000)))
        if sys.byteorder!='little':
            samples.byteswap()
        (WORK/'input.pcm').write_bytes(samples.tobytes())
        ff('-y','-f','rawvideo','-pixel_format','yuv420p','-video_size','64x48','-framerate','4','-i','input.yuv',
           '-f','s16le','-ar','48000','-ac','1','-i','input.pcm','-c:v','mpeg4','-g','4','-q:v','3','-c:a','aac','-shortest','input.mp4')

    def test_01_original_extract_transform(self):
        ff('-y','-ss','0.5','-i','input.mp4','-vf','crop=48:32:8:8,scale=96:64,transpose=1','-frames:v','1','extract.png')
        stream=probe('extract.png')['streams'][0]
        self.assertEqual((64,96),(stream['width'],stream['height']))

    def test_02_animation_full_frames_and_timestamps(self):
        pixels=b''.join(bytes([r,g,b,a])*16 for r,g,b,a in [(255,0,0,255),(0,255,0,255),(0,0,255,128),(255,255,0,255)])
        (WORK/'animation.rgba').write_bytes(pixels)
        ff('-y','-f','rawvideo','-pixel_format','rgba','-video_size','4x4','-framerate','5','-i','animation.rgba',
           '-c:v','libwebp_anim','-lossless','1','-pix_fmt','bgra','animation.webp')
        decoded=rgba('animation.webp','animation-decoded.rgba')
        self.assertEqual(pixels,decoded)
        data=probe('animation.webp',True)
        self.assertEqual(4,int(data['streams'][0]['nb_read_frames']))
        timestamps=[round(float(f['best_effort_timestamp_time']),3) for f in data['frames']]
        self.assertEqual([0.0,0.2,0.4,0.6],timestamps)
        ff('-y','-i','animation.webp','-vf','scale=8:8','-c:v','libwebp_anim','-lossless','1','scaled.webp')
        self.assertEqual(4,int(probe('scaled.webp')['streams'][0]['nb_read_frames']))
        ff('-y','-i','animation.webp','animation.gif')
        self.assertEqual(4,int(probe('animation.gif')['streams'][0]['nb_read_frames']))

    def test_03_animation_partial_frames_disposal_and_vfr(self):
        def le24(n):
            return n.to_bytes(3,'little')
        def chunk(tag,data):
            return tag+struct.pack('<I',len(data))+data+(b'\0' if len(data)%2 else b'')
        frames=[]
        for i,(w,h,color,x,y,ms,flags) in enumerate([(4,4,(255,0,0,255),0,0,100,2),(2,2,(0,255,0,255),0,0,200,3),(2,2,(0,0,255,255),2,2,300,2)]):
            name=f'part{i}'
            png_file(name+'.png',w,h,bytes(color)*(w*h))
            ff('-y','-i',name+'.png','-c:v','libwebp','-lossless','1',name+'.webp')
            data=(WORK/(name+'.webp')).read_bytes();offset=12;image=b''
            while offset<len(data):
                tag=data[offset:offset+4];length=int.from_bytes(data[offset+4:offset+8],'little');end=offset+8+length+(length%2)
                if tag in (b'ALPH',b'VP8 ',b'VP8L'):
                    image+=data[offset:end]
                offset=end
            header=le24(x//2)+le24(y//2)+le24(w-1)+le24(h-1)+le24(ms)+bytes([flags])
            frames.append(chunk(b'ANMF',header+image))
        body=b'WEBP'+chunk(b'VP8X',bytes([0x12,0,0,0])+le24(3)+le24(3))+chunk(b'ANIM',bytes(6))+b''.join(frames)
        (WORK/'partial.webp').write_bytes(b'RIFF'+struct.pack('<I',len(body))+body)
        decoded=rgba('partial.webp','partial.rgba')
        expected=[]
        for index in range(3):
            for y in range(4):
                for x in range(4):
                    color=(255,0,0,255)
                    if index==1 and x<2 and y<2:
                        color=(0,255,0,255)
                    if index==2 and x<2 and y<2:
                        color=(0,0,0,0)
                    if index==2 and x>=2 and y>=2:
                        color=(0,0,255,255)
                    expected.extend(color)
        self.assertEqual(bytes(expected),decoded)
        data=probe('partial.webp',True)
        self.assertEqual([0.0,0.1,0.3],[round(float(f['best_effort_timestamp_time']),3) for f in data['frames']])

    def test_04_opaque_redaction(self):
        ff('-y','-i','input.mp4','-vf','drawbox=x=8:y=8:w=16:h=16:color=black:t=fill','-frames:v','1','masked.png')
        pixels=rgba('masked.png','masked.rgba')
        inside=[pixels[(y*64+x)*4:(y*64+x)*4+3] for y in range(10,22) for x in range(10,22)]
        self.assertTrue(all(max(p)<=2 for p in inside))
        self.assertTrue(any(pixels[(30*64+30)*4:(30*64+30)*4+3]))

    def test_05_gaussian_blur(self):
        pixels=b''.join(bytes([255 if (x+y)%2 else 0])*3+b'\xff' for y in range(32) for x in range(32))
        png_file('noise.png',32,32,pixels)
        ff('-y','-i','noise.png','-vf','gblur=sigma=2','blur.png')
        output=rgba('blur.png','blur.rgba')
        values=[output[(y*32+x)*4] for y in range(8,24) for x in range(8,24)]
        self.assertLess(max(values)-min(values),20)

    def test_06_pixelization(self):
        pixels=b''.join(bytes([(x*7+y*11)%256])*3+b'\xff' for y in range(32) for x in range(32))
        png_file('pattern.png',32,32,pixels)
        ff('-y','-i','pattern.png','-vf','pixelize=w=8:h=8','pixelized.png')
        out=rgba('pixelized.png','pixelized.rgba')
        for by in range(0,32,8):
            for bx in range(0,32,8):
                values=[out[(y*32+x)*4] for y in range(by,by+8) for x in range(bx,bx+8)]
                self.assertLessEqual(max(values)-min(values),2)

    def test_07_select_and_contact_sheet(self):
        ff('-y','-i','input.mp4','-vf','select=not(mod(n\\,2)),scale=16:12,tile=2x2','-frames:v','1','sheet.png')
        stream=probe('sheet.png')['streams'][0]
        self.assertEqual((32,24),(stream['width'],stream['height']))
        out=rgba('sheet.png','sheet.rgba')
        centers=[sum(out[(y*32+x)*4:(y*32+x)*4+3]) for x,y in [(8,6),(24,6),(8,18),(24,18)]]
        self.assertEqual(centers,sorted(centers))
        self.assertGreater(centers[-1]-centers[0],50)

    def test_08_video_tail_padding(self):
        ff('-y','-i','input.mp4','-an','-vf','tpad=stop_mode=clone:stop_duration=1','-c:v','mpeg4','padded.mp4')
        data=probe('padded.mp4');self.assertEqual(12,int(data['streams'][0]['nb_read_frames']))
        self.assertAlmostEqual(3,float(data['format']['duration']),places=2)

    def test_09_audio_tail_padding(self):
        ff('-y','-f','s16le','-ar','48000','-ac','1','-i','input.pcm','-af','apad=pad_dur=0.5','-f','s16le','padded.pcm')
        data=(WORK/'padded.pcm').read_bytes()
        self.assertEqual(120000*2,len(data));self.assertEqual(bytes(24000*2),data[-24000*2:])

    def test_10_color_filters(self):
        ff('-y','-i','input.mp4','-vf','hue=s=0','-frames:v','1','-pix_fmt','yuv420p','-f','rawvideo','gray.yuv')
        raw=(WORK/'gray.yuv').read_bytes();self.assertTrue(all(abs(v-128)<=1 for v in raw[64*48:]))
        for expression,name in [('lutrgb=r=negval:g=negval:b=negval','lutrgb'),('lutyuv=y=val+20','lutyuv')]:
            ff('-y','-i','input.mp4','-vf',expression,'-frames:v','1',name+'.png')
            self.assertEqual(64,probe(name+'.png')['streams'][0]['width'])

    def amr(self,wide):
        # Synthetic legal fixed-rate speech frame bit patterns, not recorded speech or a quality benchmark.
        tag='wb' if wide else 'nb';header=b'#!AMR-WB\n' if wide else b'#!AMR\n'
        packet=(b'\x04'+bytes(17)) if wide else (b'\x3c'+bytes(31))
        (WORK/f'{tag}.amr').write_bytes(header+packet*10)
        info=probe(tag+'.amr');stream=info['streams'][0]
        self.assertEqual('amr_wb' if wide else 'amr_nb',stream['codec_name'])
        ff('-y','-i',tag+'.amr','-c:a','pcm_s16le','-f','s16le',tag+'.pcm')
        self.assertEqual((320 if wide else 160)*10*2,(WORK/(tag+'.pcm')).stat().st_size)

    def test_11_amr_nb_input(self):
        self.amr(False)

    def test_12_amr_wb_input(self):
        self.amr(True)

    def test_13_alac_m4a_caf_inputs(self):
        for extension in ('m4a','caf'):
            fixture_full(['-f','s16le','-ar','48000','-ac','1','-i','input.pcm','-c:a','alac','alac.'+extension],'alac-'+extension)
            self.assertEqual('alac',probe('alac.'+extension)['streams'][0]['codec_name'])
            ff('-y','-i','alac.'+extension,'-c:a','pcm_s16le','-f','s16le','alac-'+extension+'.pcm')
            self.assertEqual((WORK/'input.pcm').read_bytes(),(WORK/('alac-'+extension+'.pcm')).read_bytes())

    def test_14_aiff_and_caf_pcm(self):
        for extension,codec in [('aiff','pcm_s16be'),('caf','pcm_s24be')]:
            fixture_full(['-f','s16le','-ar','48000','-ac','1','-i','input.pcm','-c:a',codec,'pcm.'+extension],'pcm-'+extension)
            ff('-y','-i','pcm.'+extension,'-c:a','pcm_s16le','-f','s16le','back-'+extension+'.pcm')
            self.assertEqual((WORK/'input.pcm').read_bytes(),(WORK/('back-'+extension+'.pcm')).read_bytes())

    def g711(self,kind):
        fixture_full(['-f','s16le','-ar','48000','-ac','1','-i','input.pcm','-ar','8000','-c:a','pcm_'+kind,'-f',kind,kind+'.raw'],kind)
        ff('-y','-f',kind,'-ar','8000','-ac','1','-i',kind+'.raw','-c:a','pcm_s16le','-f','s16le',kind+'.pcm')
        self.assertEqual(32000,(WORK/(kind+'.pcm')).stat().st_size)

    def test_15_g711_alaw(self):
        self.g711('alaw')

    def test_16_g711_mulaw(self):
        self.g711('mulaw')

    def test_17_mp3_opus_regression(self):
        for encoder,extension,codec in [('libmp3lame','mp3','mp3'),('libopus','opus','opus')]:
            ff('-y','-i','input.mp4','-vn','-c:a',encoder,'audio.'+extension)
            self.assertEqual(codec,probe('audio.'+extension)['streams'][0]['codec_name'])
            ff('-y','-i','audio.'+extension,'-f','s16le','audio-'+extension+'.pcm')
            self.assertGreater((WORK/('audio-'+extension+'.pcm')).stat().st_size,180000)

    def test_18_concat_segment_regression(self):
        (WORK/'list.txt').write_text("file 'input.mp4'\nfile 'input.mp4'\n")
        ff('-y','-f','concat','-safe','1','-i','list.txt','-c','copy','joined.mp4')
        self.assertEqual(16,int(probe('joined.mp4')['streams'][0]['nb_read_frames']))
        ff('-y','-i','input.mp4','-an','-c','copy','-f','segment','-segment_time','1','part-%02d.mp4')
        parts=sorted(WORK.glob('part-*.mp4'));self.assertGreaterEqual(len(parts),2)
        self.assertEqual(8,sum(int(probe(p.name)['streams'][0]['nb_read_frames']) for p in parts))

    def test_19_fade_silence_loudnorm_regression(self):
        ff('-y','-i','input.mp4','-vf','fade=t=in:st=0:d=1','-frames:v','1','fade.png')
        self.assertTrue(all(v<=2 for v in rgba('fade.png','fade.rgba')[0:3]))
        log=command(BIN/'ffmpeg',['-nostdin','-hide_banner','-i','input.mp4','-vn','-af','silencedetect=noise=-45dB:d=0.2','-f','null','-'],'silence')
        self.assertIn('silence_start: 0',log)
        ff('-y','-i','input.mp4','-vn','-af','loudnorm=I=-16','-ar','48000','normalized.wav')
        self.assertEqual('48000',probe('normalized.wav')['streams'][0]['sample_rate'])

    def test_20_subtitles_and_text_regression(self):
        (WORK/'captions.srt').write_text('1\n00:00:00,000 --> 00:00:01,800\nHelix 测试\n')
        ff('-y','-i','input.mp4','-i','captions.srt','-map','0','-map','1','-c','copy','-c:s','mov_text','soft.mp4')
        self.assertIn('mov_text',{s['codec_name'] for s in probe('soft.mp4')['streams']})
        fonts=[Path('/System/Library/Fonts/Supplemental/Arial Unicode.ttf'),Path('/System/Library/Fonts/PingFang.ttc'),Path('/System/Library/Fonts/Helvetica.ttc')]
        font=next((p for p in fonts if p.is_file()),None);self.assertIsNotNone(font,'No system test font; do not invent rendering acceptance')
        ff('-y','-i','input.mp4','-vf',f'drawtext=fontfile={font}:text=Helix:fontcolor=white:fontsize=12:x=0:y=0','-frames:v','1','text.png')
        ff('-y','-i','input.mp4','-frames:v','1','unmodified.png')
        self.assertNotEqual(rgba('text.png','text.rgba'),rgba('unmodified.png','unmodified.rgba'))
        ff('-y','-i','input.mp4','-vf',f'subtitles=captions.srt:fontsdir={font.parent}','-frames:v','1','burned.png')
        self.assertNotEqual(rgba('burned.png','burned.rgba'),(WORK/'unmodified.rgba').read_bytes())

    def test_21_malformed_and_overwrite(self):
        (WORK/'bad.mp4').write_bytes(b'not media')
        log=ff('-i','bad.mp4','-f','null','-',expect=tuple(range(1,256)))
        self.assertIn('Invalid data',log)
        before=digest(WORK/'input.mp4')
        ff('-n','-i','input.mp4','-c','copy','input.mp4',expect=tuple(range(256)))
        self.assertEqual(before,digest(WORK/'input.mp4'))
        protocols=command(BIN/'ffmpeg',['-hide_banner','-protocols'],'protocols')
        self.assertNotIn('http',protocols);self.assertNotIn('tcp',protocols)

    def test_22_hdr_tagged_inputs_are_detected_not_tone_mapped(self):
        for transfer,status in [('smpte2084','hdr'),('arib-std-b67','hdr'),('bt709','sdr')]:
            output=transfer+'.mp4'
            fixture_full(['-f','rawvideo','-pixel_format','yuv420p','-video_size','64x48','-framerate','4','-i','input.yuv',
                          '-frames:v','4','-pix_fmt','yuv420p10le','-c:v','libx265','-preset','ultrafast','-threads','1',
                          '-x265-params','pools=none:frame-threads=1:log-level=error:colorprim='+('bt2020' if status=='hdr' else 'bt709')+':transfer='+transfer+':colormatrix='+('bt2020nc' if status=='hdr' else 'bt709'),'-color_trc',transfer,
                          '-color_primaries','bt2020' if status=='hdr' else 'bt709','-colorspace','bt2020nc' if status=='hdr' else 'bt709',output],'hdr-'+transfer)
            independent=json.loads(command(shutil.which('ffprobe'),['-v','error','-show_entries','stream=color_transfer,color_primaries','-of','json',output],'independent-color-probe'))
            self.assertEqual(transfer,independent['streams'][0].get('color_transfer'),'Fixture failed to encode the requested transfer function')
            stream=probe(output)['streams'][0]
            self.assertEqual(status,color_facts(stream)['status'])
            self.assertEqual(status=='sdr',guard_color(stream,pixel_operation=True)['allowed'])
            self.assertEqual(10,color_facts(stream)['bit_depth'])


class Av1Tests(MediaTests):
    # Loaded separately only for the optional AV1 candidate; no skipped tests in the base suite.
    def test_23_av1_8bit_and_10bit(self):
        for depth in (8,10):
            output=f'av1-{depth}.ivf'
            fixture_full(['-f','rawvideo','-pixel_format','yuv420p','-video_size','64x48','-framerate','4','-i','input.yuv',
                          '-vf','scale=128:96','-frames:v','4','-pix_fmt','yuv420p' if depth==8 else 'yuv420p10le',
                          '-c:v','libsvtav1','-preset','12','-svtav1-params','lp=1','-threads','1',output],f'av1-{depth}')
            stream=probe(output)['streams'][0]
            self.assertEqual('av1',stream['codec_name']);self.assertEqual(4,int(stream['nb_read_frames']))
            ff('-y','-i',output,'-frames:v','1',f'av1-{depth}.png')
            self.assertEqual((128,96),tuple(probe(f'av1-{depth}.png')['streams'][0][k] for k in ('width','height')))


class RecordingResult(unittest.TextTestResult):
    def __init__(self,*args,**kwargs):
        super().__init__(*args,**kwargs);self.records=[];self.started={}
    def startTest(self,test):
        self.started[test.id()]=time.monotonic();super().startTest(test)
    def addSuccess(self,test):
        self.records.append({'name':test.id(),'status':'passed','seconds':round(time.monotonic()-self.started[test.id()],3)})
        super().addSuccess(test)
    def addFailure(self,test,err):
        self.records.append({'name':test.id(),'status':'failed','error':self._exc_info_to_string(err,test)})
        super().addFailure(test,err)
    def addError(self,test,err):
        self.records.append({'name':test.id(),'status':'error','error':self._exc_info_to_string(err,test)})
        super().addError(test,err)


def main(profile):
    global PROFILE,WORK,BIN
    PROFILE=profile;proof,_=verify_build(profile,'host');BIN=OUT/'install'/profile/'host/bin'
    retained_sources={name:digest(HERE/name) for name in ['test_media.py','test_contract.py','contract.py','artifacts.py','kit.py','recipe.json']}
    WORK=OUT/'tests'/profile/uuid.uuid4().hex;WORK.mkdir(parents=True)
    loader=unittest.TestLoader()
    suite=unittest.TestSuite([loader.loadTestsFromModule(test_contract),loader.loadTestsFromTestCase(Av1Tests if profile=='ready-av1' else MediaTests)])
    def enumerate_tests(items):
        for item in items:
            if isinstance(item,unittest.TestSuite):
                yield from enumerate_tests(item)
            else:
                yield item.id()
    planned=list(enumerate_tests(suite))
    result=unittest.TextTestRunner(verbosity=2,resultclass=RecordingResult).run(suite)
    evidence={'profile':profile,'scope':'macOS companion, not Android device or integrated Helix',
              'build_identity':proof['key'],'install_identity':identity(proof['files']),
              'suite_sha256':digest(HERE/'test_media.py'),'contract_sha256':digest(HERE/'contract.py'),
              'contract_test_sha256':digest(HERE/'test_contract.py'),'recipe_sha256':digest(HERE/'recipe.json'),
              'artifact_tools_sha256':digest(HERE/'artifacts.py'),
              'test_count':result.testsRun,'failed':len(result.failures)+len(result.errors),'skipped':len(result.skipped),
              'planned_tests':planned,
              'tests':result.records,'run_directory':str(WORK.relative_to(OUT)),
              'device_status':'not_requested','production_integration':False}
    verify_build(profile,'host')
    require(retained_sources=={name:digest(HERE/name) for name in retained_sources},'Test sources changed during execution; results cannot certify final code')
    atomic_json(WORK/'results.json',evidence);atomic_json(OUT/f'host-results-{profile}.json',evidence)
    print(json.dumps({k:v for k,v in evidence.items() if k!='tests'}),flush=True)
    return 0 if result.wasSuccessful() else 1


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--profile',choices=['ready','ready-av1'],default='ready')
    sys.exit(main(parser.parse_args().profile))
