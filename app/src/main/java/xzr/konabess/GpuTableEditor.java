package xzr.konabess;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import xzr.konabess.adapters.ParamAdapter;
import xzr.konabess.utils.DialogUtil;
import xzr.konabess.utils.DtsHelper;

/**
 * Pineapple-P only GpuTableEditor.
 * Simplified: only supports the single-bin structure used by Pineapple-P:
 *
 * qcom,gpu-pwrlevel-bins {
 *   qcom,gpu-pwrlevels-0 {
 *     ... header lines ...
 *     qcom,gpu-pwrlevel@0 { ... }
 *     qcom,gpu-pwrlevel@1 { ... }
 *     ...
 *   };
 * };
 *
 * This file intentionally removes multi-bin/multi-variant branching to isolate G3G3.
 */
public class GpuTableEditor {
    private static int bin_position;
    private static ArrayList<bin> bins;

    private static class bin {
        int id;
        ArrayList<String> header;
        ArrayList<level> levels;
    }

    private static class level {
        ArrayList<String> lines;
    }

    private static ArrayList<String> lines_in_dts;

    /* Load the DTS file lines into memory */
    public static void init() throws IOException {
        lines_in_dts = new ArrayList<>();
        bins = new ArrayList<>();
        bin_position = -1;
        BufferedReader bufferedReader =
                new BufferedReader(new FileReader(new File(KonaBessCore.dts_path)));
        String s;
        while ((s = bufferedReader.readLine()) != null) {
            lines_in_dts.add(s);
        }
        bufferedReader.close();
    }

    /**
     * Decode: very strict Pineapple-P parsing:
     * Find top-level "qcom,gpu-pwrlevel-bins {" block and inside it locate the "qcom,gpu-pwrlevels-0 {" block.
     * Extract the header lines for gpu-pwrlevels-0 and each qcom,gpu-pwrlevel@N {} sub-block under it.
     */
    public static void decode() throws Exception {
        // reset
        bins.clear();
        bin_position = -1;

        int topStart = -1;
        int topBracket = 0;
        int i = 0;
        // find qcom,gpu-pwrlevel-bins {
        for (; i < lines_in_dts.size(); i++) {
            String line = lines_in_dts.get(i).trim();
            if (line.equals("qcom,gpu-pwrlevel-bins {")) {
                topStart = i;
                bin_position = i; // where we will insert/recreate later
                topBracket = 1;
                break;
            }
        }
        if (topStart < 0) {
            throw new Exception("qcom,gpu-pwrlevel-bins not found");
        }

        // find the child qcom,gpu-pwrlevels-0 { and its block range
        int childStart = -1;
        int childEnd = -1;
        for (int j = topStart + 1; j < lines_in_dts.size(); j++) {
            String line = lines_in_dts.get(j).trim();
            if (line.contains("{")) topBracket++;
            if (line.contains("}")) topBracket--;
            if (line.equals("qcom,gpu-pwrlevels-0 {")) {
                // find its matching close
                childStart = j;
                int bracket = 1;
                for (int k = j + 1; k < lines_in_dts.size(); k++) {
                    String inner = lines_in_dts.get(k);
                    if (inner.contains("{")) bracket++;
                    if (inner.contains("}")) bracket--;
                    if (bracket == 0) {
                        childEnd = k;
                        break;
                    }
                }
                break;
            }
            if (topBracket == 0) break; // ended top without finding child
        }

        if (childStart < 0 || childEnd < 0) {
            throw new Exception("qcom,gpu-pwrlevels-0 not found inside gpu-pwrlevel-bins");
        }

        // Parse the child block into a single bin object
        List<String> childBlock = lines_in_dts.subList(childStart, childEnd + 1);
        decode_bin(childBlock); // decode single bin (it expects a block starting with qcom,gpu-pwrlevels-0 {)

        // remove the full wrapper block (gpu-pwrlevel-bins ... ) from lines_in_dts so we can re-insert later
        // find top's matching end:
        int wrapperStart = topStart;
        int wrapperBracket = 1;
        int wrapperEnd = -1;
        for (int k = topStart + 1; k < lines_in_dts.size(); k++) {
            String l = lines_in_dts.get(k);
            if (l.contains("{")) wrapperBracket++;
            if (l.contains("}")) wrapperBracket--;
            if (wrapperBracket == 0) {
                wrapperEnd = k;
                break;
            }
        }
        if (wrapperEnd < 0) {
            throw new Exception("unable to find end of gpu-pwrlevel-bins wrapper");
        }
        // remove wrapper so genBack can insert our generated one exactly at bin_position
        lines_in_dts.subList(wrapperStart, wrapperEnd + 1).clear();
    }

    /*
     * helper used by decode() to parse a qcom,gpu-pwrlevels-0 { ... } block (or a gpu-pwrlevels-0-like list)
     * The passed 'lines' should contain the block starting with "qcom,gpu-pwrlevels-0 {" and ending with the matching "}".
     */
    private static void decode_bin(List<String> lines) throws Exception {
        bin b = new bin();
        b.header = new ArrayList<>();
        b.levels = new ArrayList<>();
        b.id = 0;

        int i = 0;
        int bracket = 0;
        int start = -1;
        while (i < lines.size()) {
            String raw = lines.get(i);
            String line = raw.trim();
            if (i == 0) {
                // expect "qcom,gpu-pwrlevels-0 {"
                i++;
                continue;
            }

            if (line.equals("") || line.equals("}")) {
                i++;
                continue;
            }

            if (line.contains("{")) {
                // start of a level block (qcom,gpu-pwrlevel@N {)
                start = i;
                bracket = 1;
                for (int k = i + 1; k < lines.size(); k++) {
                    String in = lines.get(k);
                    if (in.contains("{")) bracket++;
                    if (in.contains("}")) bracket--;
                    if (bracket == 0) {
                        // slice start..k inclusive as a level block
                        List<String> levelBlock = lines.subList(start, k + 1);
                        b.levels.add(decode_level(levelBlock));
                        i = k + 1;
                        break;
                    }
                }
                continue;
            }

            // if we reach here and not inside a level, treat as header
            b.header.add(raw);
            i++;
        }

        bins.add(b);
    }

    private static level decode_level(List<String> lines) {
        level lvl = new level();
        lvl.lines = new ArrayList<>();
        for (String line : lines) {
            String t = line.trim();
            if (t.contains("{") || t.contains("}")) continue;
            if (t.contains("reg")) continue;
            lvl.lines.add(t);
        }
        return lvl;
    }

    /* Generate the frequency table lines (to be inserted into DTS). Always outputs wrapper structure. */
    public static List<String> genTable() {
        ArrayList<String> lines = new ArrayList<>();

        // wrapper start
        lines.add("qcom,gpu-pwrlevel-bins {");
        lines.add("    compatible = \"qcom,gpu-pwrlevel-bins\";");
        lines.add("    #size-cells = <0x00>;");
        lines.add("    #address-cells = <0x01>;");
        // start child
        lines.add("    qcom,gpu-pwrlevels-0 {");
        // insert header (header lines are assumed to be already trimmed/valid)
        for (String h : bins.get(0).header) {
            lines.add("        " + h);
        }
        // add levels
        for (int pwr_level_id = 0; pwr_level_id < bins.get(0).levels.size(); pwr_level_id++) {
            lines.add("        qcom,gpu-pwrlevel@" + pwr_level_id + " {");
            lines.add("            reg = <" + pwr_level_id + ">;");
            for (String l : bins.get(0).levels.get(pwr_level_id).lines) {
                lines.add("            " + l);
            }
            lines.add("        };");
        }
        // close child and wrapper
        lines.add("    };"); // close qcom,gpu-pwrlevels-0
        lines.add("};"); // close qcom,gpu-pwrlevel-bins

        return lines;
    }

    /* Insert generated 'table' back into the saved DTS lines (recreate wrapper where we removed it in decode()). */
    public static List<String> genBack(List<String> table) {
        ArrayList<String> new_dts = new ArrayList<>(lines_in_dts);
        if (bin_position < 0) {
            // fallback: append at end
            new_dts.addAll(table);
        } else {
            new_dts.addAll(bin_position, table);
        }
        return new_dts;
    }

    public static void writeOut(List<String> new_dts) throws IOException {
        File file = new File(KonaBessCore.dts_path);
        file.createNewFile();
        BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(file));
        for (String s : new_dts) {
            bufferedWriter.write(s);
            bufferedWriter.newLine();
        }
        bufferedWriter.close();
    }

    private static String generateSubtitle(String line) throws Exception {
        String raw_name = DtsHelper.decode_hex_line(line).name;
        if ("qcom,level".equals(raw_name) || "qcom,cx-level".equals(raw_name)) {
            return GpuVoltEditor.levelint2str(DtsHelper.decode_int_line(line).value);
        }
        return DtsHelper.shouldUseHex(line) ? DtsHelper.decode_hex_line(line).value :
                DtsHelper.decode_int_line(line).value + "";
    }

    private static void generateALevel(Activity activity, int last, int levelid,
                                       LinearLayout page) throws Exception {
        ((MainActivity) activity).onBackPressedListener = new MainActivity.onBackPressedListener() {
            @Override
            public void onBackPressed() {
                try {
                    generateLevels(activity, last, page);
                } catch (Exception ignored) {
                }
            }
        };

        ListView listView = new ListView(activity);
        ArrayList<ParamAdapter.item> items = new ArrayList<>();

        items.add(new ParamAdapter.item() {{
            title = activity.getResources().getString(R.string.back);
            subtitle = "";
        }});

        for (String line : bins.get(last).levels.get(levelid).lines) {
            items.add(new ParamAdapter.item() {{
                title = KonaBessStr.convert_level_params(DtsHelper.decode_hex_line(line).name,
                        activity);
                subtitle = generateSubtitle(line);
            }});
        }

        listView.setOnItemClickListener((parent, view, position, id) -> {
            try {
                if (position == 0) {
                    generateLevels(activity, last, page);
                    return;
                }
                String raw_name =
                        DtsHelper.decode_hex_line(bins.get(last).levels.get(levelid).lines.get(position - 1)).name;
                String raw_value =
                        DtsHelper.shouldUseHex(bins.get(last).levels.get(levelid).lines.get(position - 1))
                                ?
                                DtsHelper.decode_hex_line(bins.get(last).levels.get(levelid).lines.get(position - 1)).value
                                :
                                DtsHelper.decode_int_line(bins.get(last).levels.get(levelid).lines.get(position - 1)).value + "";

                if (raw_name.equals("qcom,level") || raw_name.equals("qcom,cx-level")) {
                    try {
                        Spinner spinner = new Spinner(activity);
                        spinner.setAdapter(new ArrayAdapter(activity,
                                android.R.layout.simple_dropdown_item_1line,
                                ChipInfo.rpmh_levels.level_str()));
                        spinner.setSelection(GpuVoltEditor.levelint2int(Integer.parseInt(raw_value)));

                        new AlertDialog.Builder(activity)
                                .setTitle(R.string.edit)
                                .setView(spinner)
                                .setMessage(R.string.editvolt_msg)
                                .setPositiveButton(R.string.save, (dialog, which) -> {
                                    try {
                                        bins.get(last).levels.get(levelid).lines.set(
                                                position - 1,
                                                DtsHelper.encodeIntOrHexLine(raw_name,
                                                        ChipInfo.rpmh_levels.levels()[spinner.getSelectedItemPosition()] + ""));
                                        generateALevel(activity, last, levelid, page);
                                        Toast.makeText(activity, R.string.save_success,
                                                Toast.LENGTH_SHORT).show();
                                    } catch (Exception exception) {
                                        DialogUtil.showError(activity, R.string.save_failed);
                                        exception.printStackTrace();
                                    }
                                })
                                .setNegativeButton(R.string.cancel, null)
                                .create().show();

                    } catch (Exception e) {
                        DialogUtil.showError(activity, R.string.error_occur);
                    }
                } else {
                    EditText editText = new EditText(activity);
                    editText.setInputType(DtsHelper.shouldUseHex(raw_name) ?
                            InputType.TYPE_CLASS_TEXT : InputType.TYPE_CLASS_NUMBER);
                    editText.setText(raw_value);
                    new AlertDialog.Builder(activity)
                            .setTitle(activity.getResources().getString(R.string.edit) + " \"" + items.get(position).title + "\"")
                            .setView(editText)
                            .setMessage(KonaBessStr.help(raw_name, activity))
                            .setPositiveButton(R.string.save, (dialog, which) -> {
                                try {
                                    bins.get(last).levels.get(levelid).lines.set(
                                            position - 1,
                                            DtsHelper.encodeIntOrHexLine(raw_name,
                                                    editText.getText().toString()));
                                    generateALevel(activity, last, levelid, page);
                                    Toast.makeText(activity, R.string.save_success,
                                            Toast.LENGTH_SHORT).show();
                                } catch (Exception e) {
                                    DialogUtil.showError(activity, R.string.save_failed);
                                }
                            })
                            .setNegativeButton(R.string.cancel, null)
                            .create().show();
                }
            } catch (Exception e) {
                DialogUtil.showError(activity, R.string.error_occur);
            }
        });

        listView.setAdapter(new ParamAdapter(items, activity));

        page.removeAllViews();
        page.addView(listView);
    }

    private static level level_clone(level from) {
        level next = new level();
        next.lines = new ArrayList<>(from.lines);
        return next;
    }

    /**
     * offset_initial_level_old walks the DTS to find initial-pwrlevel in gpu block.
     * Keep it (used as fallback in some flows) but Pineapple-P uses header-based handler.
     */
    private static void offset_initial_level_old(int offset) throws Exception {
        boolean started = false;
        int bracket = 0;
        for (int i = 0; i < lines_in_dts.size(); i++) {
            String line = lines_in_dts.get(i);

            if (line.contains("qcom,kgsl-3d0") && line.contains("{")) {
                started = true;
                bracket++;
                continue;
            }

            if (line.contains("{")) {
                bracket++;
                continue;
            }

            if (line.contains("}")) {
                bracket--;
                if (bracket == 0)
                    break;
                continue;
            }

            if (!started)
                continue;

            if (line.contains("qcom,initial-pwrlevel")) {
                lines_in_dts.set(i,
                        DtsHelper.encodeIntOrHexLine(DtsHelper.decode_int_line(line).name,
                                DtsHelper.decode_int_line(line).value + offset + ""));
            }

        }
    }

    /**
     * For Pineapple-P we operate on bins.get(0).header to change initial-pwrlevel.
     * This method will update that header entry.
     */
    private static void offset_initial_level(int bin_id, int offset) throws Exception {
        // Only single bin exists; update the header entry (if present)
        for (int i = 0; i < bins.get(0).header.size(); i++) {
            String line = bins.get(0).header.get(i);
            if (line.contains("qcom,initial-pwrlevel")) {
                bins.get(0).header.set(i,
                        DtsHelper.encodeIntOrHexLine(
                                DtsHelper.decode_int_line(line).name,
                                DtsHelper.decode_int_line(line).value + offset + ""));
                break;
            }
        }
    }

    private static void offset_ca_target_level(int bin_id, int offset) throws Exception {
        for (int i = 0; i < bins.get(0).header.size(); i++) {
            String line = bins.get(0).header.get(i);
            if (line.contains("qcom,ca-target-pwrlevel")) {
                bins.get(0).header.set(i,
                        DtsHelper.encodeIntOrHexLine(
                                DtsHelper.decode_int_line(line).name,
                                DtsHelper.decode_int_line(line).value + offset + ""));
                break;
            }
        }
    }

    private static void patch_throttle_level_old() throws Exception {
        boolean started = false;
        int bracket = 0;
        for (int i = 0; i < lines_in_dts.size(); i++) {
            String line = lines_in_dts.get(i);

            if (line.contains("qcom,kgsl-3d0") && line.contains("{")) {
                started = true;
                bracket++;
                continue;
            }

            if (line.contains("{")) {
                bracket++;
                continue;
            }

            if (line.contains("}")) {
                bracket--;
                if (bracket == 0)
                    break;
                continue;
            }

            if (!started)
                continue;

            if (line.contains("qcom,throttle-pwrlevel")) {
                lines_in_dts.set(i,
                        DtsHelper.encodeIntOrHexLine(DtsHelper.decode_int_line(line).name,
                                "0"));
            }

        }
    }

    /**
     * For Pineapple-P we look for throttle-pwrlevel inside the header we parsed and set it to 0.
     */
    private static void patch_throttle_level() throws Exception {
        for (int i = 0; i < bins.get(0).header.size(); i++) {
            String line = bins.get(0).header.get(i);
            if (line.contains("qcom,throttle-pwrlevel")) {
                bins.get(0).header.set(i,
                        DtsHelper.encodeIntOrHexLine(
                                DtsHelper.decode_int_line(line).name, "0"));
                break;
            }
        }
    }

    public static boolean canAddNewLevel(int binID, Context context) throws Exception {
        // use chip max from ChipInfo; Pineapple-P is single-bin and should use 16 or 11 depending on type; assume 16 to be safe
        int max_levels = ChipInfo.getMaxTableLevels(ChipInfo.type.pineapplep_singleBin) - min_level_chip_offset();
        if (bins.get(binID).levels.size() <= max_levels)
            return true;
        Toast.makeText(context, R.string.unable_add_more, Toast.LENGTH_SHORT).show();
        return false;
    }

    public static int min_level_chip_offset() throws Exception {
        // Pineapple-P behaves as single-bin style offsets of 1
        return 1;
    }

    private static void generateLevels(Activity activity, int id, LinearLayout page) throws Exception {
        ((MainActivity) activity).onBackPressedListener = new MainActivity.onBackPressedListener() {
            @Override
            public void onBackPressed() {
                try {
                    generateBins(activity, page);
                } catch (Exception ignored) {
                }
            }
        };

        ListView listView = new ListView(activity);
        ArrayList<ParamAdapter.item> items = new ArrayList<>();

        items.add(new ParamAdapter.item() {{
            title = activity.getResources().getString(R.string.back);
            subtitle = "";
        }});

        items.add(new ParamAdapter.item() {{
            title = activity.getResources().getString(R.string.new_item);
            subtitle = activity.getResources().getString(R.string.new_desc);
        }});

        for (level level : bins.get(id).levels) {
            long freq = getFrequencyFromLevel(level);
            if (freq == 0)
                continue;
            ParamAdapter.item item = new ParamAdapter.item();
            item.title = freq / 1000000 + "MHz";
            item.subtitle = "";
            items.add(item);
        }

        items.add(new ParamAdapter.item() {{
            title = activity.getResources().getString(R.string.new_item);
            subtitle = activity.getResources().getString(R.string.new_desc);
        }});

        listView.setOnItemClickListener((parent, view, position, id1) -> {
            if (position == items.size() - 1) {
                try {
                    if (!canAddNewLevel(id, activity))
                        return;
                    bins.get(id).levels.add(bins.get(id).levels.size() - min_level_chip_offset(),
                            level_clone(bins.get(id).levels.get(bins.get(id).levels.size() - min_level_chip_offset())));
                    generateLevels(activity, id, page);
                    offset_initial_level(id, 1);
                    offset_ca_target_level(id, 1);
                } catch (Exception e) {
                    DialogUtil.showError(activity, R.string.error_occur);
                }
                return;
            }
            if (position == 0) {
                try {
                    generateBins(activity, page);
                } catch (Exception ignored) {
                }
                return;
            }
            if (position == 1) {
                try {
                    if (!canAddNewLevel(id, activity))
                        return;
                    bins.get(id).levels.add(0, level_clone(bins.get(id).levels.get(0)));
                    generateLevels(activity, id, page);
                    offset_initial_level(id, 1);
                    offset_ca_target_level(id, 1);
                } catch (Exception e) {
                    DialogUtil.showError(activity, R.string.error_occur);
                }
                return;
            }
            position -= 2;
            try {
                generateALevel(activity, id, position, page);
            } catch (Exception e) {
                DialogUtil.showError(activity, R.string.error_occur);
            }
        });

        listView.setOnItemLongClickListener((parent, view, position, idd) -> {
            if (position == items.size() - 1)
                return true;
            if (bins.get(id).levels.size() == 1)
                return true;
            try {
                new AlertDialog.Builder(activity)
                        .setTitle(R.string.remove)
                        .setMessage(String.format(activity.getResources().getString(R.string.remove_msg),
                                getFrequencyFromLevel(bins.get(id).levels.get(position - 2)) / 1000000))
                        .setPositiveButton(R.string.yes, (dialog, which) -> {
                            bins.get(id).levels.remove(position - 2);
                            try {
                                generateLevels(activity, id, page);
                                offset_initial_level(id, -1);
                                offset_ca_target_level(id, -1);
                            } catch (Exception e) {
                                DialogUtil.showError(activity, R.string.error_occur);
                            }
                        })
                        .setNegativeButton(R.string.no, null)
                        .create().show();
            } catch (Exception ignored) {
                ignored.printStackTrace();
            }
            return true;
        });

        listView.setAdapter(new ParamAdapter(items, activity));

        page.removeAllViews();
        page.addView(listView);
    }

    private static long getFrequencyFromLevel(level level) throws Exception {
        for (String line : level.lines) {
            if (line.contains("qcom,gpu-freq")) {
                return DtsHelper.decode_int_line(line).value;
            }
        }
        return 0;
    }

    private static void generateBins(Activity activity, LinearLayout page) throws Exception {
        ((MainActivity) activity).onBackPressedListener = new MainActivity.onBackPressedListener() {
            @Override
            public void onBackPressed() {
                ((MainActivity) activity).showMainView();
            }
        };

        ListView listView = new ListView(activity);
        ArrayList<ParamAdapter.item> items = new ArrayList<>();

        // Only a single bin for Pineapple-P
        ParamAdapter.item item = new ParamAdapter.item();
        item.title = KonaBessStr.convert_bins_pineapplep_singleBin(0, activity);
        item.subtitle = "";
        items.add(item);

        listView.setAdapter(new ParamAdapter(items, activity));
        listView.setOnItemClickListener((parent, view, position, id) -> {
            try {
                generateLevels(activity, position, page);
            } catch (Exception e) {
                DialogUtil.showError(activity, R.string.error_occur);
            }
        });

        page.removeAllViews();
        page.addView(listView);
    }

    private static View generateToolBar(Activity activity) {
        LinearLayout toolbar = new LinearLayout(activity);
        HorizontalScrollView horizontalScrollView = new HorizontalScrollView(activity);
        horizontalScrollView.addView(toolbar);

        {
            Button button = new Button(activity);
            button.setText(R.string.save_freq_table);
            toolbar.addView(button);
            button.setOnClickListener(v -> {
                try {
                    writeOut(genBack(genTable()));
                    Toast.makeText(activity, R.string.save_success, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    DialogUtil.showError(activity, R.string.save_failed);
                }
            });
        }
        return horizontalScrollView;
    }

    static class gpuTableLogic extends Thread {
        Activity activity;
        AlertDialog waiting;
        LinearLayout showedView;
        LinearLayout page;

        public gpuTableLogic(Activity activity, LinearLayout showedView) {
            this.activity = activity;
            this.showedView = showedView;
        }

        public void run() {
            activity.runOnUiThread(() -> {
                waiting = DialogUtil.getWaitDialog(activity, R.string.getting_freq_table);
                waiting.show();
            });

            try {
                init();
                decode();                // parse Pineapple-P block
                patch_throttle_level();  // patch header throttle
            } catch (Exception e) {
                activity.runOnUiThread(() -> DialogUtil.showError(activity,
                        R.string.getting_freq_table_failed));
                return;
            }

            activity.runOnUiThread(() -> {
                waiting.dismiss();
                showedView.removeAllViews();
                showedView.addView(generateToolBar(activity));
                page = new LinearLayout(activity);
                page.setOrientation(LinearLayout.VERTICAL);
                try {
                    generateBins(activity, page);
                } catch (Exception e) {
                    DialogUtil.showError(activity, R.string.getting_freq_table_failed);
                }
                showedView.addView(page);
            });

        }
    }
}
