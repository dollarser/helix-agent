"""Host-only regression tests for preparation contracts and trustworthy build evidence."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest

from contract import MediaContractError, bounded_frame_plan, color_facts, guard_color, number, relative_reference, target_video_bitrate, trim_request, verify_result
from kit import atomic_json, components, identity, proof_matches, run, tree_manifest, validate_components


class ContractTests(unittest.TestCase):
    def test_finite_numbers_not_booleans(self):
        for bad in (True,False,None,{},'NaN','Infinity',float('inf'),'-1','1e1000000000','1e-1000000000','9'*65):
            with self.subTest(value=str(bad)),self.assertRaises(MediaContractError):
                number(bad,'value')

    def test_lexical_scope_and_unicode(self):
        self.assertEqual('目录/带 空格.mp4',relative_reference('目录/带 空格.mp4'))
        for bad in ('../a','/tmp/a','a/../../b','content://other','a\x00b','a\\b','.',''):
            with self.subTest(value=bad),self.assertRaises(MediaContractError):
                relative_reference(bad)

    def test_trim_precision_is_not_implicit(self):
        fast=trim_request('0.2','1.8','2',mode='fast_stream_copy')
        self.assertFalse(fast['frame_exact_guaranteed'])
        self.assertFalse(fast['requires_verified_encoder'])
        exact=trim_request('0.2','1.8','2',mode='accurate_reencode')
        self.assertTrue(exact['requires_verified_encoder'])
        for begin,end,total in [('2','1','3'),('0','0','1'),('0','3','2')]:
            with self.assertRaises(MediaContractError):
                trim_request(begin,end,total,mode='accurate_reencode')

    def test_target_size_is_estimate_until_measured(self):
        estimate=target_video_bitrate(1_000_000,10,128000,100000)
        self.assertEqual(592000,estimate['suggested_video_bps'])
        self.assertFalse(estimate['size_guaranteed'])
        for params in [(10,10,128000,1),(10,1,0,10),(100,0,0,1),(100,1,0,-1)]:
            with self.assertRaises(MediaContractError):
                target_video_bitrate(*params)

    def test_ten_bit_sdr_is_not_hdr(self):
        stream={'codec_type':'video','pix_fmt':'yuv420p10le','color_transfer':'bt709'}
        self.assertEqual(10,color_facts(stream)['bit_depth'])
        self.assertEqual('sdr',color_facts(stream)['status'])
        self.assertTrue(guard_color(stream,pixel_operation=True)['allowed'])

    def test_pq_hlg_and_dolby_metadata_cannot_silently_be_sdr(self):
        for transfer in ('smpte2084','arib-std-b67'):
            stream={'codec_type':'video','color_transfer':transfer,'pix_fmt':'p010le'}
            self.assertEqual(10,color_facts(stream)['bit_depth'])
            self.assertFalse(guard_color(stream,pixel_operation=True)['allowed'])
            self.assertTrue(guard_color(stream,pixel_operation=False)['allowed'])
        stream={'codec_type':'video','side_data_list':[{'side_data_type':'DOVI configuration record'}]}
        self.assertEqual('hdr_metadata_unclassified',color_facts(stream)['status'])

    def test_conflicting_or_missing_color_requires_decision(self):
        unknown={'codec_type':'video','pix_fmt':'yuv420p'}
        self.assertEqual('unknown',color_facts(unknown)['status'])
        self.assertFalse(guard_color(unknown,pixel_operation=True)['allowed'])
        conflict={**unknown,'color_transfer':'bt709','side_data_list':[{'side_data_type':'Mastering display metadata'}]}
        self.assertEqual('conflicting',color_facts(conflict)['status'])
        self.assertFalse(guard_color(conflict,pixel_operation=True)['allowed'])

    def test_frame_resources_are_host_bounded(self):
        self.assertEqual(['0','1'],bounded_frame_plan([0,1],2,2,200,10,10))
        for args in [([0,2],2,2,200,10,10),([0,1],2,1,200,10,10),([0,1],2,2,199,10,10),([float('nan')],2,2,200,10,10)]:
            with self.assertRaises(MediaContractError):
                bounded_frame_plan(*args)

    def test_empty_mediacodec_container_is_not_success(self):
        with self.assertRaises(MediaContractError):
            verify_result({'streams':[]},261,expected_video=True)
        with self.assertRaises(MediaContractError):
            verify_result({'streams':[{'codec_type':'video','width':320,'height':240,'nb_read_frames':'0'}]},500,expected_video=True)
        good={'streams':[{'codec_type':'video','width':320,'height':240,'nb_read_frames':'30'}]}
        verify_result(good,1000,expected_video=True,max_bytes=1000)
        with self.assertRaises(MediaContractError):
            verify_result(good,1001,expected_video=True,max_bytes=1000)


class EvidenceTests(unittest.TestCase):
    def test_cache_binds_key_artifacts_and_unexpected_files(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);install=root/'install';install.mkdir();file=install/'lib.a';file.write_bytes(b'original')
            proof=root/'proof.json';atomic_json(proof,{'key':'toolchain-a','files':tree_manifest(install)})
            self.assertTrue(proof_matches(proof,'toolchain-a',install))
            self.assertFalse(proof_matches(proof,'toolchain-b',install))
            file.write_bytes(b'changed')
            self.assertFalse(proof_matches(proof,'toolchain-a',install))
            file.write_bytes(b'original');(install/'stale.so').write_bytes(b'x')
            self.assertFalse(proof_matches(proof,'toolchain-a',install))

    def test_escaping_source_symlink_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);(root/'link').symlink_to('/tmp')
            with self.assertRaises(RuntimeError):
                tree_manifest(root)

    def test_atomic_json_failure_preserves_old_evidence(self):
        with tempfile.TemporaryDirectory() as folder:
            file=Path(folder)/'state.json';atomic_json(file,{'old':True})
            with self.assertRaises(ValueError):
                atomic_json(file,{'bad':float('nan')})
            self.assertEqual({'old':True},json.loads(file.read_text()))
            self.assertEqual(['state.json'],[p.name for p in Path(folder).iterdir()])

    def test_all_requested_components_and_protocols_are_enforced(self):
        desired=components('ready','arm64-v8a')
        config='\n'.join(f'#define CONFIG_{name.upper()}_{kind.upper()} 1' for kind,names in desired.items() for name in names)
        general='\n'.join(f'#define CONFIG_{name} 0' for name in ['GPL','NONFREE','VERSION3','NETWORK','AVDEVICE'])
        validate_components(config,general,'ready','arm64-v8a')
        for changed in [config.replace('#define CONFIG_WEBP_ANIM_DECODER 1',''),config+'\n#define CONFIG_HTTP_PROTOCOL 1']:
            with self.assertRaises(RuntimeError):
                validate_components(changed,general,'ready','arm64-v8a')
        with self.assertRaises(RuntimeError):
            validate_components(config,general.replace('CONFIG_GPL 0','CONFIG_GPL 1'),'ready','arm64-v8a')

    def test_passing_count_cannot_certify_another_binary(self):
        from artifacts import validate_test_evidence
        from kit import HERE,digest
        proof={'key':'current-build','files':{'library':'current'}}
        evidence={'profile':'ready','build_identity':'current-build','install_identity':identity(proof['files']),
                  'suite_sha256':digest(HERE/'test_media.py'),'contract_sha256':digest(HERE/'contract.py'),
                  'contract_test_sha256':digest(HERE/'test_contract.py'),'recipe_sha256':digest(HERE/'recipe.json'),
                  'artifact_tools_sha256':digest(HERE/'artifacts.py'),
                  'test_count':1,'failed':0,'skipped':0,'tests':[{'name':'required','status':'passed'}],'planned_tests':['required']}
        validate_test_evidence(evidence,proof,'ready')
        for key,value in [('build_identity','stale-build'),('install_identity','old-libraries'),('suite_sha256','old-tests'),('planned_tests',['required','missing'])]:
            with self.subTest(field=key),self.assertRaises(RuntimeError):
                validate_test_evidence({**evidence,key:value},proof,'ready')

    def test_package_failure_preserves_previous_candidate(self):
        from artifacts import zip_bytes
        from kit import OUT
        OUT.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=OUT) as folder:
            file=Path(folder)/'candidate.zip';file.write_bytes(b'previous-candidate')
            with self.assertRaises(RuntimeError):
                zip_bytes(file,{'lib/arm64-v8a/libx.so':b'new','font.ttf':b'never distribute'})
            self.assertEqual(b'previous-candidate',file.read_bytes())

    def test_identical_payload_has_deterministic_zip(self):
        from artifacts import zip_bytes
        from kit import OUT
        OUT.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=OUT) as folder:
            entries={'lib/arm64-v8a/libx.so':b'fixed','NOTICE.txt':b'fixed'}
            first=zip_bytes(Path(folder)/'a.zip',entries)
            second=zip_bytes(Path(folder)/'b.zip',entries)
            self.assertEqual(first['sha256'],second['sha256'])

    def test_process_group_timeout_is_not_success(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);log=root/'timeout.log';pidfile=root/'child.pid'
            child='import time; time.sleep(120)'
            parent='import subprocess,sys,time; from pathlib import Path; p=subprocess.Popen([sys.executable,"-c",'+repr(child)+']); Path('+repr(str(pidfile))+').write_text(str(p.pid)); time.sleep(120)'
            with self.assertRaises(RuntimeError):
                run([sys.executable,'-c',parent],log=log,timeout=1)
            self.assertTrue(json.loads(log.with_suffix('.log.json').read_text())['timed_out'])
            pid=int(pidfile.read_text());state=subprocess.run(['ps','-o','stat=','-p',str(pid)],capture_output=True,text=True).stdout.strip()
            self.assertTrue(not state or state.startswith('Z'),f'Child still running: {pid}: {state}')


if __name__=='__main__':
    unittest.main(verbosity=2)
