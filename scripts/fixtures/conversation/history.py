"""Bounded exact-title lookup shared by conversation recovery fixtures."""
import time


def find_session(title, label, *, nodes, adb, pause=time.sleep):
    # Historical fixture timestamps can sort below newer independent journeys.
    # Scroll the actual list; exact title matching remains mandatory.
    for page in range(30):
        view = nodes(label + '-' + str(page))
        found = next((node for node in view if node.get('text') == title), None)
        if found is not None:
            return found
        adb('shell', 'input', 'swipe', '500', '1700', '500', '550', '300')
        pause(.3)
    raise RuntimeError('Seeded session not found in bounded history traversal: ' + title)
