"""Host source invariants; not a substitute for emulator behavior acceptance."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
EVAL = ROOT / 'app/src/androidTestDeveloper/kotlin/com/helix/app/eval'


class AndroidWorldGuardContract(unittest.TestCase):
    def source(self, name):
        return (EVAL / name).read_text()

    def test_existing_grant_binds_before_scoped_reconnect(self):
        for name in ('AndroidWorldGuardRetestDeviceTest.kt',
                     'AndroidWorldApi33BrowserSafeEvidenceDeviceTest.kt'):
            with self.subTest(name=name):
                source = self.source(name)
                self.assertLess(source.index('Helix Accessibility was not already granted'),
                                source.index('val initialBindDeadline'))
                self.assertLess(source.index('val initialBindDeadline'),
                                source.index('serviceRestarted = true'))
                self.assertIn('FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES', source)
                self.assertIn('FLAG_REPORT_VIEW_IDS', source)
                self.assertRegex(source, r'GrantedAccessibilityReconnect\s*\.awaitRemoved\(\s*app,\s*component,?\s*\)\s*\{\s*readShell\(ui, "dumpsys accessibility"\)\s*\}')
                self.assertIn('settings put secure accessibility_enabled 1', source)
                self.assertNotIn('SystemClock.sleep(250)', source)
                self.assertRegex(source, r'finally\s*\{\s*writeServices\(ui, services\)')

    def test_pending_framework_binding_is_not_mistaken_for_absent_bound_service(self):
        source = self.source('GrantedAccessibilityReconnect.kt')
        self.assertIn('frameworkBindingCleared(frameworkState(), component)', source)
        self.assertIn('Binding services:', source)
        self.assertIn('bindings.isNotEmpty()', source)
        self.assertIn('host recovery and non-restarting instrumentation required', source)

    def test_brightness_dismissal_is_navigation_only(self):
        source = self.source('GuardSystemNavigation.kt')
        self.assertIn('target == null && active == "com.android.systemui"', source)
        self.assertIn('action == "dismiss_notification_shade"', source)
        for action in ('power_dialog', 'lock_screen', 'headset_hook'):
            self.assertNotIn('"' + action + '"', source)
        self.assertIn('GuardSystemNavigation.allowed(target, active, action)',
                      self.source('AndroidWorldGuardRetestDeviceTest.kt'))

    def test_browser_completion_observation_retains_scope_boundary(self):
        source = self.source('AndroidWorldApi33BrowserSafeEvidenceDeviceTest.kt')
        observation = source.split('val observationPackages =', 1)[1].split('val sessionId =', 1)[0]
        self.assertIn('"com.helix.agent.developer"', observation)
        self.assertIn('activePackage in scope.allowedPackages', source)
        self.assertIn('!scope.allApplications', source)
        self.assertIn('depth <= 100', source)
        self.assertIn('visit(child, inside, depth + 1, path + index)', source)
        self.assertIn('BROWSER_TREE_CHILD_UNAVAILABLE', source)
        self.assertIn('BrowserTaskFileTarget.allowed(selected.fileIdentity(), fileNodes)', source)
        self.assertIn('BROWSER_LOCAL_URI_UNVERIFIED', source)
        self.assertIn('BROWSER_NATIVE_OVERLAY_PRESENT', source)

    def test_resolver_uses_live_package_and_exact_row_ancestry(self):
        source = self.source('AndroidWorldApi33BrowserSafeEvidenceDeviceTest.kt')
        self.assertIn('node.packageName?.toString().orEmpty()', source)
        self.assertIn('BrowserResolverTarget.allowed(selected.resolverIdentity()', source)
        policy = self.source('BrowserResolverTarget.kt')
        for invariant in ('singleOrNull', 'resolver_list', 'button_once',
                          'candidate.path.size == list.path.size + 1', 'descendant(chrome.path, candidate.path)',
                          'selected == row || selected == chrome'):
            self.assertIn(invariant, policy)
        self.assertNotIn('button_always', policy)

    def test_brightness_geometry_and_file_observation_remain_restricted(self):
        source = self.source('AndroidWorldGuardRetestDeviceTest.kt')
        # Geometry moved into a bounded helper; check both its wiring and retained safeguards.
        self.assertIn('GuardBrightnessGeometry.verify(points, observed, ui, refused)', source)
        self.assertIn('geometryRefusal ?:', source)
        self.assertIn('GuardUiGeometry.withinBounds(input, appBounds)', source)
        source += self.source('GuardBrightnessGeometry.kt')
        for invariant in ('strokes.size != 1', 'points.size < 2', 'y == firstY',
                          'liveBounds != rectangle', 'root.windowId != windowId',
                          'overlappingControl', 'BRIGHTNESS_OVERLAPPING_CONTROL',
                          'android.view.View', 'Build.VERSION.SDK_INT == 33', 'node.childCount == 0',
                          '!node.isLongClickable', '!node.isCheckable',
                          'if (active != target) return false',
                          'task == "FilesMoveFile" && activePackage == "com.google.android.apps.photos"'):
            self.assertIn(invariant, source)
        self.assertNotIn('AutomationObserver(', source)


if __name__ == '__main__':
    unittest.main()
