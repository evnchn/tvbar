#!/bin/bash
# End-to-end check of the hold-Back trigger and the ◀ guide with kernel-level key presses. Run on live TV with the bar collapsed.
set -uo pipefail
# Never use `uiautomator dump` around this: on this TV it unbinds the accessibility key service until it is re-enabled.
A="adb -s ${TV:?set TV=<tv-ip>:5555}"
tvkey() { "$(dirname "$0")/tvkey" "$@"; }
focus() { $A shell 'dumpsys window windows | grep mCurrentFocus' | tr -d '\r'; }
fail() { echo "FAIL: $*"; exit 1; }
focus | grep -q 'com.evnchn.tvbar' && fail "bar already expanded; collapse it first"
tvkey BACK 2500 & sleep 1.6        # still holding
focus | grep -q 'com.evnchn.tvbar' || { wait; fail "hold Back did not open the panel: $(focus)"; }
$A exec-out screencap -p > /tmp/tvbar-test-open.png   # visual evidence: the panel as the TV draws it
wait; sleep 1.5                                  # released
focus | grep -q 'com.evnchn.tvbar' || fail "panel closed by itself on release: $(focus)"
tvkey BACK 100; sleep 1.5
focus | grep -q 'com.evnchn.tvbar' && fail "tap Back did not close the panel"
# Guide: ◀ on bare live TV opens it; ◀ inside the vendor menu must still reach the menu.
tvkey LEFT; sleep 1.5
focus | grep -q 'com.evnchn.tvbar' || fail "◀ on live TV did not open the guide"
tvkey BACK; sleep 1.2
tvkey MENU; sleep 1.5; tvkey LEFT; sleep 1
focus | grep -q 'com.evnchn.tvbar' && { tvkey BACK; fail "◀ inside the vendor menu was swallowed"; }
tvkey BACK; sleep 1.2
echo "PASS: hold Back opens, panel stays, tap Back closes; ◀ opens the guide on live TV and not inside the menu. Screenshot: /tmp/tvbar-test-open.png"
