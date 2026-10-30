import unittest
from editor_preferences import merge_preferences
class MergeTests(unittest.TestCase):
 def test_preserves_new_tv_changes_and_history(self):
  current={'language':'en','spotifyTransparency':70,'recent':['new'],'lastApp':'spotify','home':['youtube'],'dock':['youtube'],'look':{'font':'geist'}}
  out,conflict=merge_preferences(current,{'language':'en','spotifyTransparency':60},{'language':'de'})
  self.assertEqual(conflict,{});self.assertEqual(out['spotifyTransparency'],70);self.assertEqual(out['recent'],['new']);self.assertEqual(out['lastApp'],'spotify');self.assertEqual(out['language'],'de')
 def test_refuses_conflicting_edit(self):
  out,conflict=merge_preferences({'spotifyTransparency':80},{'spotifyTransparency':60},{'spotifyTransparency':70})
  self.assertIsNone(out);self.assertIn('spotifyTransparency',conflict)
 def test_already_applied_and_legacy_fields(self):
  out,conflict=merge_preferences({'home':['a'],'dock':['a'],'wall':'summit','wallpaper':'summit','look':{'wall':'summit','font':'geist'}},{'home':['a'],'wall':'summit'},{'home':['a'],'wall':'cove'})
  self.assertFalse(conflict);self.assertEqual(out['wallpaper'],'cove');self.assertEqual(out['look']['wall'],'cove');self.assertEqual(out['look']['font'],'geist')
if __name__=='__main__':unittest.main()
