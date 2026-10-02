"""Pure integration-preparation rules. No tools are registered and no media action is executed here.

A future Android host must provide authorization, file descriptors, execution ownership,
font discovery and verified device capabilities. These helpers grant none of those.
"""
from __future__ import annotations
from decimal import Decimal, InvalidOperation, ROUND_FLOOR
import math
from pathlib import PurePosixPath
import re
from typing import Any


class MediaContractError(ValueError):
    pass


def number(value: Any, name: str, *, minimum: Decimal = Decimal(0), positive: bool = False) -> Decimal:
    if isinstance(value, bool) or not isinstance(value, (int, float, str, Decimal)):
        raise MediaContractError(f'{name}: expected finite number')
    try:
        text = str(value)
        if len(text) > 64:
            raise MediaContractError(f'{name}: oversized numeric token')
        result = Decimal(text)
    except (InvalidOperation, ValueError) as exc:
        raise MediaContractError(f'{name}: invalid number') from exc
    # Avoid allocating enormous integers from tiny exponent strings; timestamps use at most ns precision.
    if (not result.is_finite() or result.copy_abs() > Decimal(2**63-1) or
            result.as_tuple().exponent < -9 or result < minimum or (positive and result == minimum)):
        raise MediaContractError(f'{name}: outside permitted range')
    return result


def positive_int(value: Any, name: str) -> int:
    parsed = number(value, name, positive=True)
    if parsed != parsed.to_integral_value():
        raise MediaContractError(f'{name}: integer required')
    return int(parsed)


def relative_reference(value: Any) -> str:
    if not isinstance(value, str) or not value or '\\' in value or ':' in value or any(ord(c) < 32 or ord(c) == 127 for c in value):
        raise MediaContractError('Invalid relative input reference')
    path = PurePosixPath(value)
    if path.is_absolute() or '..' in path.parts or not path.parts or path == PurePosixPath('.'):
        raise MediaContractError('Input reference escapes the granted scope')
    # This is lexical validation only; the host must resolve/open without following escaping links.
    return path.as_posix()


def trim_request(start: Any, end: Any, duration: Any, *, mode: str) -> dict:
    begin = number(start,'start')
    finish = number(end,'end',positive=True)
    total = number(duration,'duration',positive=True)
    if not begin < finish <= total:
        raise MediaContractError('Trim range must satisfy 0 <= start < end <= duration')
    if mode not in ('accurate_reencode','fast_stream_copy'):
        raise MediaContractError('Choose explicit accurate or keyframe-dependent trim')
    return {'start_seconds':str(begin),'end_seconds':str(finish),'duration_seconds':str(finish-begin),
            'mode':mode,'frame_exact_guaranteed':False,'accuracy_requires_output_verification':True,
            'requires_verified_encoder':mode == 'accurate_reencode'}


def target_video_bitrate(target_bytes: Any, duration_seconds: Any, audio_bps: Any, reserve_bytes: Any) -> dict:
    target = positive_int(target_bytes,'target_bytes')
    duration = number(duration_seconds,'duration',positive=True)
    audio = number(audio_bps,'audio_bps')
    reserve = number(reserve_bytes,'reserve_bytes')
    if reserve >= target:
        raise MediaContractError('Container reserve consumes the target')
    video = ((Decimal(target)-reserve)*8/duration-audio).to_integral_value(rounding=ROUND_FLOOR)
    if video <= 0:
        raise MediaContractError('Target cannot fit the requested audio and container allowance')
    return {'suggested_video_bps':int(video),'target_bytes':target,
            'size_guaranteed':False,'postcondition':'verify actual output byte length before publication'}


def color_facts(stream: dict) -> dict:
    if not isinstance(stream,dict) or stream.get('codec_type') != 'video':
        raise MediaContractError('Select an exact video stream')
    transfer = str(stream.get('color_transfer') or 'unknown').lower()
    primaries = str(stream.get('color_primaries') or 'unknown').lower()
    pixel_format = str(stream.get('pix_fmt') or 'unknown')
    depth = None
    raw_depth = stream.get('bits_per_raw_sample')
    if raw_depth not in (None,'0',0,'unknown','N/A'):
        try:
            parsed = positive_int(raw_depth,'bits_per_raw_sample')
            depth = parsed if parsed <= 64 else None
        except MediaContractError:
            pass
    if depth is None:
        match = re.search(r'(?:p|p0|p2|p4)(10|12|14|16)(?:le|be)$',pixel_format)
        if match:
            depth = int(match.group(1))
        elif pixel_format in {'yuv420p','yuv422p','yuv444p','yuva420p','nv12','nv21','rgb24','rgba','bgra','gray'}:
            depth = 8
    side_data = stream.get('side_data_list',[])
    if not isinstance(side_data,list) or any(not isinstance(item,dict) for item in side_data):
        raise MediaContractError('Malformed color side data')
    side_types = [str(item.get('side_data_type','')).lower() for item in side_data if isinstance(item,dict)]
    hdr_metadata = any(any(name in item for name in ('mastering display','content light level','dovi','dolby vision','hdr10','gain map','gainmap')) for item in side_types)
    known_hdr = transfer in {'smpte2084','arib-std-b67'}
    known_sdr = transfer in {'bt709','smpte170m','smpte240m','iec61966-2-1','gamma22','gamma28','bt2020-10','bt2020-12'}
    status = 'hdr' if known_hdr else ('conflicting' if known_sdr and hdr_metadata else ('hdr_metadata_unclassified' if hdr_metadata else ('sdr' if known_sdr else 'unknown')))
    return {'status':status,'transfer':transfer,'primaries':primaries,
            'range':stream.get('color_range','unknown'),'pixel_format':pixel_format,'bit_depth':depth,
            'metadata_present':hdr_metadata,'ten_bit_is_not_proof_of_hdr':True}


def guard_color(stream: dict, *, pixel_operation: bool) -> dict:
    facts = color_facts(stream)
    if not pixel_operation:
        return {'allowed':True,'facts':facts,'note':'Observation/remux only; preserve and verify metadata'}
    if facts['status'] != 'sdr':
        return {'allowed':False,'facts':facts,
                'reason':'VERIFIED_HDR_PIPELINE_REQUIRED' if facts['status'] != 'unknown' else 'INPUT_COLOR_UNSPECIFIED'}
    return {'allowed':True,'facts':facts,'note':'Use the explicit SDR color declaration; no HDR conversion claimed'}


def bounded_frame_plan(times: list, duration: Any, host_max_frames: int, host_max_total_pixels: int,
                       width: int, height: int) -> list[str]:
    if not isinstance(times,list) or not times:
        raise MediaContractError('At least one timestamp is required')
    count = positive_int(host_max_frames,'host_max_frames')
    pixels = positive_int(host_max_total_pixels,'host_max_total_pixels')
    w,h = positive_int(width,'width'),positive_int(height,'height')
    total = number(duration,'duration',positive=True)
    if len(times) > count or len(times)*w*h > pixels:
        raise MediaContractError('Frame request exceeds the host resource budget')
    parsed = [number(value,'timestamp') for value in times]
    if any(value >= total for value in parsed):
        raise MediaContractError('Frame timestamp lies outside media')
    return [str(value) for value in parsed]


def verify_result(probe: dict, output_bytes: int, *, expected_video: bool = False,
                  expected_audio: bool = False, max_bytes: int | None = None) -> None:
    if not isinstance(output_bytes,int) or isinstance(output_bytes,bool) or output_bytes <= 0:
        raise MediaContractError('Empty output is not successful completion')
    streams = probe.get('streams')
    if not isinstance(streams,list) or not streams:
        raise MediaContractError('No output streams; exit 0 is not completion')
    for kind,required in (('video',expected_video),('audio',expected_audio)):
        selected = [s for s in streams if isinstance(s,dict) and s.get('codec_type') == kind]
        if required and not selected:
            raise MediaContractError(f'Missing expected {kind} stream')
        if kind == 'video' and required:
            for stream in selected:
                positive_int(stream.get('width'), 'width')
                positive_int(stream.get('height'), 'height')
                positive_int(stream.get('nb_read_frames'), 'decoded frame count')
    if max_bytes is not None and output_bytes > positive_int(max_bytes,'max_bytes'):
        raise MediaContractError('Actual output exceeds requested byte ceiling')
