package com.micromeasure;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements MeasureView.Listener {

    private static final int REQ_CAMERA = 1;
    private static final int REQ_GALLERY = 2;
    private static final int MAX_DIM = 4096;

    private MeasureView mv;
    private TextView status;
    private Button btnCalib;
    private CalibStore store;
    private Uri cameraUri;
    private int origW, origH;
    private float dp;

    private static class Loaded {
        Bitmap bmp;
        int w, h;
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        dp = getResources().getDisplayMetrics().density;
        store = new CalibStore(this);
        if (saved != null) cameraUri = saved.getParcelable("cameraUri");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF111111);

        status = new TextView(this);
        status.setTextColor(0xFFFFFFFF);
        status.setTextSize(14);
        int p = (int) (10 * dp);
        status.setPadding(p, p, p, p);
        status.setBackgroundColor(0xFF263238);
        status.setOnClickListener(v -> chooseProfile());
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mv = new MeasureView(this);
        mv.setListener(this);
        root.addView(mv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setBackgroundColor(0xFF1C1C1C);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding((int) (4 * dp), (int) (4 * dp), (int) (4 * dp), (int) (4 * dp));
        addBtn(bar, "📷 촬영", v -> takePhoto());
        addBtn(bar, "🖼 갤러리", v -> pickGallery());
        btnCalib = addBtn(bar, "📏 보정", v -> onCalibButton());
        addBtn(bar, "🔍 배율선택", v -> chooseProfile());
        addBtn(bar, "↶ 취소", v -> mv.undo());
        addBtn(bar, "🗑 전체삭제", v -> confirmClear());
        addBtn(bar, "⤢ 화면맞춤", v -> mv.fitToView());
        addBtn(bar, "💾 저장", v -> save());
        hs.addView(bar);
        root.addView(hs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        applyProfile(false);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (cameraUri != null) out.putParcelable("cameraUri", cameraUri);
    }

    private Button addBtn(LinearLayout bar, String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins((int) (2 * dp), 0, (int) (2 * dp), 0);
        bar.addView(b, lp);
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ───────────── 사진 불러오기 ─────────────

    private void takePhoto() {
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, "MM_" + stamp() + ".jpg");
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MicroMeasure");
            cameraUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_CAMERA);
        } catch (ActivityNotFoundException e) {
            toast("카메라 앱을 찾을 수 없습니다");
        } catch (Exception e) {
            toast("카메라 실행 실패: " + e.getMessage());
        }
    }

    private void pickGallery() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        try {
            startActivityForResult(i, REQ_GALLERY);
        } catch (ActivityNotFoundException e) {
            toast("갤러리 앱을 찾을 수 없습니다");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_GALLERY && res == RESULT_OK && data != null && data.getData() != null) {
            loadImage(data.getData());
        } else if (req == REQ_CAMERA) {
            if (res == RESULT_OK && cameraUri != null) {
                loadImage(cameraUri);
            } else if (cameraUri != null) {
                try { getContentResolver().delete(cameraUri, null, null); } catch (Exception ignored) { }
                cameraUri = null;
            }
        }
    }

    private void loadImage(Uri uri) {
        status.setText("사진 불러오는 중...");
        new Thread(() -> {
            try {
                Loaded l = decode(uri);
                runOnUiThread(() -> {
                    origW = l.w;
                    origH = l.h;
                    if (mv.getMode() == MeasureView.MODE_CALIB) exitCalib();
                    mv.setImage(l.bmp, (float) l.w / l.bmp.getWidth());
                    applyProfile(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    toast("불러오기 실패: " + e.getMessage());
                    updateStatus();
                });
            }
        }).start();
    }

    private Loaded decode(Uri uri) throws IOException {
        ContentResolver cr = getContentResolver();
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream is = cr.openInputStream(uri)) {
            BitmapFactory.decodeStream(is, null, o);
        }
        int w = o.outWidth, h = o.outHeight;
        if (w <= 0 || h <= 0) throw new IOException("지원하지 않는 이미지 형식");

        int sample = 1;
        while (Math.max(w, h) / sample > MAX_DIM) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        o2.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bm;
        try (InputStream is = cr.openInputStream(uri)) {
            bm = BitmapFactory.decodeStream(is, null, o2);
        }
        if (bm == null) throw new IOException("이미지 디코딩 실패");

        int orient = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream is = cr.openInputStream(uri)) {
            orient = new ExifInterface(is).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception ignored) { }
        int deg = 0;
        if (orient == ExifInterface.ORIENTATION_ROTATE_90) deg = 90;
        else if (orient == ExifInterface.ORIENTATION_ROTATE_180) deg = 180;
        else if (orient == ExifInterface.ORIENTATION_ROTATE_270) deg = 270;
        if (deg != 0) {
            Matrix m = new Matrix();
            m.postRotate(deg);
            Bitmap r = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            if (r != bm) bm.recycle();
            bm = r;
        }

        Loaded l = new Loaded();
        l.bmp = bm;
        boolean swap = (deg == 90 || deg == 270);
        l.w = swap ? h : w;
        l.h = swap ? w : h;
        return l;
    }

    // ───────────── 캘리브레이션 ─────────────

    private void onCalibButton() {
        if (mv.getBitmap() == null) {
            toast("먼저 기준 눈금(대물 마이크로미터 등) 사진을 불러오세요");
            return;
        }
        if (mv.getMode() == MeasureView.MODE_MEASURE) {
            mv.setMode(MeasureView.MODE_CALIB);
            btnCalib.setText("✔ 보정완료");
            updateStatus();
            return;
        }
        if (!mv.hasCalibLine()) {
            new AlertDialog.Builder(this)
                    .setMessage("기준선이 없습니다. 보정을 취소할까요?")
                    .setPositiveButton("보정 취소", (d, w) -> exitCalib())
                    .setNegativeButton("계속", null)
                    .show();
            return;
        }
        showCalibDialog();
    }

    private void showCalibDialog() {
        final double px = mv.getCalibOrigPx();
        if (px < 1) {
            toast("기준선이 너무 짧습니다");
            return;
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (20 * dp);
        box.setPadding(p, (int) (8 * dp), p, 0);

        TextView info = new TextView(this);
        info.setText(String.format(Locale.US, "기준선 길이: %.1f px", px));
        box.addView(info);

        final EditText name = new EditText(this);
        name.setHint("배율 이름 (예: x100, 대물10x)");
        name.setSingleLine(true);
        CalibStore.Profile cur = store.getSelected();
        if (cur != null) name.setText(cur.name);
        box.addView(name);

        final EditText len = new EditText(this);
        len.setHint("기준선의 실제 길이");
        len.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(len);

        final RadioGroup unit = new RadioGroup(this);
        unit.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rUm = new RadioButton(this);
        rUm.setText("µm");
        rUm.setId(View.generateViewId());
        RadioButton rMm = new RadioButton(this);
        rMm.setText("mm");
        rMm.setId(View.generateViewId());
        unit.addView(rUm);
        unit.addView(rMm);
        unit.check(rUm.getId());
        final int mmId = rMm.getId();
        box.addView(unit);

        new AlertDialog.Builder(this)
                .setTitle("보정값 저장")
                .setView(box)
                .setPositiveButton("저장", (d, w) -> {
                    String n = name.getText().toString().trim();
                    double v;
                    try {
                        v = Double.parseDouble(len.getText().toString().trim());
                    } catch (Exception e) {
                        toast("실제 길이를 숫자로 입력하세요");
                        return;
                    }
                    if (n.isEmpty() || v <= 0) {
                        toast("배율 이름과 길이를 입력하세요");
                        return;
                    }
                    double um = (unit.getCheckedRadioButtonId() == mmId) ? v * 1000.0 : v;
                    CalibStore.Profile pr = new CalibStore.Profile();
                    pr.name = n;
                    pr.umPerPx = um / px;
                    pr.imgW = origW;
                    pr.imgH = origH;
                    store.save(pr);
                    store.setSelected(n);
                    exitCalib();
                    applyProfile(false);
                    toast(String.format(Locale.US, "'%s' 저장: %.4f µm/px", n, pr.umPerPx));
                })
                .setNeutralButton("보정 취소", (d, w) -> exitCalib())
                .setNegativeButton("선 다시 조정", null)
                .show();
    }

    private void exitCalib() {
        mv.clearCalib();
        mv.setMode(MeasureView.MODE_MEASURE);
        btnCalib.setText("📏 보정");
        updateStatus();
    }

    private void chooseProfile() {
        final List<CalibStore.Profile> list = store.list();
        if (list.isEmpty()) {
            toast("저장된 보정값이 없습니다. 기준 눈금 사진으로 '📏 보정'을 먼저 하세요");
            return;
        }
        String sel = store.getSelectedName();
        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            CalibStore.Profile p = list.get(i);
            items[i] = (p.name.equals(sel) ? "✔ " : "    ") + p.name
                    + String.format(Locale.US, "  (%.4f µm/px)", p.umPerPx);
        }
        new AlertDialog.Builder(this)
                .setTitle("배율(보정값) 선택")
                .setItems(items, (d, which) -> {
                    store.setSelected(list.get(which).name);
                    applyProfile(true);
                })
                .setNeutralButton("보정 없음(px)", (d, w) -> {
                    store.setSelected(null);
                    applyProfile(false);
                })
                .setNegativeButton("삭제…", (d, w) -> deleteProfile(list))
                .show();
    }

    private void deleteProfile(final List<CalibStore.Profile> list) {
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) names[i] = list.get(i).name;
        new AlertDialog.Builder(this)
                .setTitle("삭제할 보정값")
                .setItems(names, (d, which) -> new AlertDialog.Builder(this)
                        .setMessage("'" + names[which] + "' 보정값을 삭제할까요?")
                        .setPositiveButton("삭제", (d2, w2) -> {
                            store.delete(names[which]);
                            applyProfile(false);
                        })
                        .setNegativeButton("취소", null)
                        .show())
                .show();
    }

    private void applyProfile(boolean warnRes) {
        CalibStore.Profile p = store.getSelected();
        mv.setUmPerPx(p == null ? 0 : p.umPerPx);
        if (warnRes && p != null && p.imgW > 0 && origW > 0) {
            boolean same = (p.imgW == origW && p.imgH == origH) || (p.imgW == origH && p.imgH == origW);
            if (!same) {
                toast(String.format(Locale.US,
                        "⚠ 사진 해상도(%dx%d)가 보정 때(%dx%d)와 다릅니다.\n카메라 해상도·줌 설정을 확인하세요.",
                        origW, origH, p.imgW, p.imgH));
            }
        }
        updateStatus();
    }

    private String profileCaption() {
        CalibStore.Profile p = store.getSelected();
        if (p == null) return "보정 없음 (px 단위)";
        return String.format(Locale.US, "배율 %s · %.4f µm/px", p.name, p.umPerPx);
    }

    // ───────────── 상태 표시 ─────────────

    @Override
    public void onMeasureChanged() {
        updateStatus();
    }

    private void updateStatus() {
        if (status == null || mv == null) return;
        if (mv.getMode() == MeasureView.MODE_CALIB) {
            String s = "📏 보정 모드: 기준 눈금의 두 끝을 탭하세요 (점을 끌면 미세조정)";
            if (mv.hasCalibLine()) {
                s += String.format(Locale.US, "\n기준선 %.1f px → '✔ 보정완료'를 누르세요", mv.getCalibOrigPx());
            }
            status.setText(s);
            return;
        }
        String s = "🔍 " + profileCaption() + "   (탭하여 변경)";
        List<MeasureView.Seg> segs = mv.getSegs();
        if (mv.getBitmap() == null) {
            s += "\n사진을 불러오세요";
        } else if (segs.isEmpty()) {
            s += "\n시작점·끝점 탭 → 거리 표시 · 점 끌기 = 미세조정 · 두 손가락 = 확대/이동";
        } else {
            MeasureView.Seg last = segs.get(segs.size() - 1);
            s += "\n측정 " + segs.size() + "개 · 마지막 #" + segs.size() + ": " + mv.formatDist(mv.segOrigPx(last));
        }
        status.setText(s);
    }

    // ───────────── 저장 ─────────────

    private void confirmClear() {
        if (mv.getSegs().isEmpty()) return;
        new AlertDialog.Builder(this)
                .setMessage("측정선을 모두 지울까요?")
                .setPositiveButton("삭제", (d, w) -> mv.clearAll())
                .setNegativeButton("취소", null)
                .show();
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    private void save() {
        if (mv.getBitmap() == null) {
            toast("사진이 없습니다");
            return;
        }
        if (mv.getSegs().isEmpty()) {
            toast("측정선이 없습니다");
            return;
        }
        final String caption = profileCaption();
        final String ts = stamp();
        final Bitmap out;
        try {
            out = mv.renderAnnotated(caption);
        } catch (OutOfMemoryError e) {
            toast("메모리 부족으로 저장 실패");
            return;
        }
        final String csv = buildCsv(caption);
        final ContentResolver cr = getContentResolver();
        status.setText("저장 중...");

        new Thread(() -> {
            try {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, "MM_meas_" + ts + ".jpg");
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MicroMeasure");
                Uri u = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (u == null) throw new IOException("사진 저장 위치 생성 실패");
                try (OutputStream os = cr.openOutputStream(u)) {
                    out.compress(Bitmap.CompressFormat.JPEG, 95, os);
                }
                out.recycle();

                ContentValues cv2 = new ContentValues();
                cv2.put(MediaStore.Downloads.DISPLAY_NAME, "MM_meas_" + ts + ".csv");
                cv2.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
                cv2.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MicroMeasure");
                Uri u2 = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv2);
                if (u2 == null) throw new IOException("CSV 저장 위치 생성 실패");
                try (OutputStream os = cr.openOutputStream(u2)) {
                    os.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}); // 엑셀 한글 깨짐 방지
                    os.write(csv.getBytes(StandardCharsets.UTF_8));
                }
                runOnUiThread(() -> {
                    toast("저장 완료\n사진: Pictures/MicroMeasure\nCSV: Download/MicroMeasure");
                    updateStatus();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    toast("저장 실패: " + e.getMessage());
                    updateStatus();
                });
            }
        }).start();
    }

    private String buildCsv(String caption) {
        StringBuilder sb = new StringBuilder();
        sb.append("보정,").append(caption.replace(",", " ")).append('\n');
        sb.append(String.format(Locale.US, "사진해상도,%dx%d\n", origW, origH));
        sb.append("번호,거리(표시),거리_um,거리_px,x1_px,y1_px,x2_px,y2_px\n");
        List<MeasureView.Seg> segs = mv.getSegs();
        double k = mv.getUmPerPx();
        for (int i = 0; i < segs.size(); i++) {
            MeasureView.Seg s = segs.get(i);
            double px = mv.segOrigPx(s);
            sb.append(i + 1).append(',')
                    .append(mv.formatDist(px)).append(',')
                    .append(k > 0 ? String.format(Locale.US, "%.3f", px * k) : "").append(',')
                    .append(String.format(Locale.US, "%.2f,%.1f,%.1f,%.1f,%.1f",
                            px, mv.toOrig(s.a.x), mv.toOrig(s.a.y), mv.toOrig(s.b.x), mv.toOrig(s.b.y)))
                    .append('\n');
        }
        return sb.toString();
    }
}
