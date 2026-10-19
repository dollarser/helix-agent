import unittest
from score import distance, mixed_tokens, normalize, summarize


class ScoringTest(unittest.TestCase):
    def test_normalization_retains_number_spelling(self):
        self.assertEqual(normalize('ＡＰＩ， 123。'), 'api123')
        self.assertNotEqual(normalize('十二'), normalize('12'))

    def test_edit_distance_counts_insertions_deletions_substitutions(self):
        self.assertEqual(distance('你好世界', '你们世界啊'), 2)
        self.assertEqual(distance('', '幻觉'), 2)
        self.assertEqual(distance('abc', ''), 3)

    def test_mixed_metric_uses_english_words_and_chinese_characters(self):
        self.assertEqual(mixed_tokens('更新 Android API 36。'), ['更', '新', 'android', 'api', '36'])

    def test_unknown_marker_is_one_error_unit(self):
        self.assertEqual(len(normalize('<unk>')), 1)
        self.assertEqual(len(mixed_tokens('<unk>')), 1)

    def test_script_normalization_preserves_strict_score(self):
        row = dict(model='a', group='zh', reference='后台', text='後臺', audioMs=100,
                   computeMs=50, loadMs=2, firstTextMs=-1, finishMs=4)
        result = summarize([row], lambda text: text.replace('後', '后').replace('臺', '台'))[0]
        self.assertEqual(result['errorRate'], 0)
        self.assertEqual(result['strictErrorRate'], 1)

    def test_paced_end_delay_includes_last_chunk_processing(self):
        row = dict(model='a', group='zh', reference='字', text='字', audioMs=1000,
                   computeMs=300, elapsedMs=1125, loadMs=2, firstTextMs=800,
                   finishMs=25, paced=True)
        result = summarize([row])[0]
        self.assertEqual(result['medianAudioEndToFinalMs'], 125)
        self.assertEqual(result['medianFinishMs'], 25)

    def test_micro_average_not_average_of_clip_percentages(self):
        rows = [dict(model='a', group='zh', reference=r, text=h, audioMs=100,
                     computeMs=50, loadMs=2, firstTextMs=-1, finishMs=4)
                for r, h in [('你好', '你'), ('一二三四五六七八', '一二三四五六七八')]]
        self.assertEqual(summarize(rows)[0]['errorRate'], 0.1)


if __name__ == '__main__':
    unittest.main()
