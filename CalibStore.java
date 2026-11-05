package com.micromeasure;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 배율별 캘리브레이션 값(µm/px)을 폰에 저장. */
public class CalibStore {

    public static class Profile {
        public String name;
        public double umPerPx;   // 원본 사진 1픽셀당 µm
        public int imgW, imgH;   // 보정에 쓴 사진 해상도 (해상도 불일치 경고용)
    }

    private static final String KEY_LIST = "profiles";
    private static final String KEY_SEL = "selected";
    private final SharedPreferences sp;

    public CalibStore(Context c) {
        sp = c.getSharedPreferences("calib", Context.MODE_PRIVATE);
    }

    public List<Profile> list() {
        List<Profile> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp.getString(KEY_LIST, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Profile p = new Profile();
                p.name = o.getString("name");
                p.umPerPx = o.getDouble("umPerPx");
                p.imgW = o.optInt("imgW", 0);
                p.imgH = o.optInt("imgH", 0);
                out.add(p);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private void write(List<Profile> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Profile p : list) {
                JSONObject o = new JSONObject();
                o.put("name", p.name);
                o.put("umPerPx", p.umPerPx);
                o.put("imgW", p.imgW);
                o.put("imgH", p.imgH);
                arr.put(o);
            }
        } catch (Exception ignored) { }
        sp.edit().putString(KEY_LIST, arr.toString()).apply();
    }

    /** 같은 이름이 있으면 덮어씀. */
    public void save(Profile p) {
        List<Profile> list = list();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name.equals(p.name)) { list.set(i, p); write(list); return; }
        }
        list.add(p);
        write(list);
    }

    public void delete(String name) {
        List<Profile> list = list();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).name.equals(name)) list.remove(i);
        }
        write(list);
        if (name.equals(getSelectedName())) setSelected(null);
    }

    public String getSelectedName() {
        return sp.getString(KEY_SEL, null);
    }

    public void setSelected(String name) {
        if (name == null) sp.edit().remove(KEY_SEL).apply();
        else sp.edit().putString(KEY_SEL, name).apply();
    }

    public Profile getSelected() {
        String n = getSelectedName();
        if (n == null) return null;
        for (Profile p : list()) if (p.name.equals(n)) return p;
        return null;
    }
}
