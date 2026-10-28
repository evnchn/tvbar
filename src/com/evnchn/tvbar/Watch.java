package com.evnchn.tvbar;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Calendar;

// Which channels this household watches at this time of the week, from KeyService's per-minute counts.
class Watch {
    // "d1h20c" = weekend, 20:00-20:59. `shift` moves by whole hours, so Saturday 00:00 - 1 h lands on Friday (a weekday).
    static String slot(Calendar now, int shift) {
        Calendar t = (Calendar) now.clone();
        t.add(Calendar.HOUR_OF_DAY, shift);
        int d = t.get(Calendar.DAY_OF_WEEK), weekend = d == Calendar.SATURDAY || d == Calendar.SUNDAY ? 1 : 0;
        return "d" + weekend + "h" + t.get(Calendar.HOUR_OF_DAY) + "c";
    }

    // Up to 3 channel numbers, best first: this hour counts double, the hours either side once.
    // A channel needs 10 points (about 5 minutes this hour) to show, so a stray channel-surf doesn't.
    static String frequent(Context c) {
        SharedPreferences sp = c.getSharedPreferences("watch", Context.MODE_PRIVATE);
        Calendar now = Calendar.getInstance();
        java.util.Map<Integer, Integer> score = new java.util.HashMap<Integer, Integer>();
        for (java.util.Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            for (int shift = -1; shift <= 1; shift++) {
                String p = slot(now, shift);
                if (!e.getKey().startsWith(p)) continue;
                int ch = Integer.parseInt(e.getKey().substring(p.length())), n = (Integer) e.getValue() * (shift == 0 ? 2 : 1);
                score.put(ch, (score.containsKey(ch) ? score.get(ch) : 0) + n);
            }
        }
        java.util.List<Integer> chs = new java.util.ArrayList<Integer>(score.keySet());
        final java.util.Map<Integer, Integer> s = score;
        java.util.Collections.sort(chs, new java.util.Comparator<Integer>() {
            public int compare(Integer a, Integer b) { return s.get(b) - s.get(a); } });
        org.json.JSONArray out = new org.json.JSONArray();
        for (int ch : chs) if (out.length() < 3 && s.get(ch) >= 10) out.put(ch);
        return out.toString();
    }
}
