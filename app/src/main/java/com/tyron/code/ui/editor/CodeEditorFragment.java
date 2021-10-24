package com.tyron.code.ui.editor;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tyron.ProjectManager;
import com.tyron.builder.model.Project;
import com.tyron.builder.parser.FileManager;
import com.tyron.code.R;
import com.tyron.code.lint.LintIssue;
import com.tyron.code.ui.editor.language.LanguageManager;
import com.tyron.code.ui.editor.language.java.JavaAnalyzer;
import com.tyron.code.ui.editor.language.java.JavaLanguage;
import com.tyron.code.ui.editor.language.xml.LanguageXML;
import com.tyron.code.ui.editor.shortcuts.ShortcutAction;
import com.tyron.code.ui.editor.shortcuts.ShortcutItem;
import com.tyron.code.ui.main.MainViewModel;
import com.tyron.common.SharedPreferenceKeys;
import com.tyron.completion.ParseTask;
import com.tyron.completion.Parser;
import com.tyron.completion.action.CodeActionProvider;
import com.tyron.completion.model.CodeAction;
import com.tyron.completion.model.CodeActionList;
import com.tyron.completion.model.Range;
import com.tyron.completion.model.TextEdit;
import com.tyron.completion.provider.CompletionEngine;
import com.tyron.completion.provider.CompletionProvider;
import com.tyron.completion.rewrite.AddImport;
import com.tyron.lint.api.TextFormat;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import io.github.rosemoe.sora.interfaces.EditorLanguage;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.Cursor;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula;

@SuppressWarnings("FieldCanBeLocal")
public class CodeEditorFragment extends Fragment implements SharedPreferences.OnSharedPreferenceChangeListener {

    private LinearLayout mRoot;
    private LinearLayout mContent;
    private CodeEditor mEditor;

    private EditorLanguage mLanguage;
    private File mCurrentFile = new File("");
    private MainViewModel mMainViewModel;
    private SharedPreferences mPreferences;

    public static CodeEditorFragment newInstance(File file) {
        CodeEditorFragment fragment = new CodeEditorFragment();
        Bundle args = new Bundle();
        args.putString("path", file.getAbsolutePath());
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mCurrentFile = new File(requireArguments().getString("path", ""));
        mMainViewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
    }

    @Override
    public void onPause() {
        super.onPause();

        hideEditorWindows();
    }

    @Override
    public void onResume() {
        super.onResume();

        if (!CompletionEngine.isIndexing()) {
            mEditor.analyze();
        }
    }

    public void hideEditorWindows() {
        mEditor.getTextActionPresenter().onExit();
        mEditor.hideAutoCompleteWindow();
    }

    @Override
    public void onStart() {
        super.onStart();

        if (!CompletionEngine.isIndexing()) {
            mEditor.analyze();
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext());

        mRoot = (LinearLayout) inflater.inflate(R.layout.code_editor_fragment, container, false);
        mContent = mRoot.findViewById(R.id.content);

        mEditor = new CodeEditor(requireActivity());
        mEditor.setEditorLanguage(mLanguage = LanguageManager.getInstance().get(mEditor, mCurrentFile));
        mEditor.setColorScheme(new SchemeDarcula());
        mEditor.setOverScrollEnabled(false);
        mEditor.setTextSize(Integer.parseInt(mPreferences.getString(SharedPreferenceKeys.FONT_SIZE, "12")));
        mEditor.setCurrentFile(mCurrentFile);
        mEditor.setTextActionMode(CodeEditor.TextActionMode.POPUP_WINDOW);
        if (mPreferences.getBoolean(SharedPreferenceKeys.KEYBOARD_ENABLE_SUGGESTIONS, false)) {
            mEditor.setInputType(EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS | EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE | EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        } else {
            mEditor.setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE);
        }
        mEditor.setTypefaceText(ResourcesCompat.getFont(requireContext(), R.font.jetbrains_mono_regular));
        mEditor.setLigatureEnabled(true);
        mEditor.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        mContent.addView(mEditor, new FrameLayout.LayoutParams(-1, -1));

        mPreferences.registerOnSharedPreferenceChangeListener(this);
        return mRoot;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences pref, String key) {
        if (mEditor == null) {
            return;
        }
        switch (key) {
            case SharedPreferenceKeys.FONT_SIZE:
                mEditor.setTextSize(Integer.parseInt(pref.getString(key, "14")));
                break;
            case SharedPreferenceKeys.KEYBOARD_ENABLE_SUGGESTIONS:
                if (pref.getBoolean(key, false)) {
                    mEditor.setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE);
                } else {
                    mEditor.setInputType(EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS | EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE | EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                }
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (mCurrentFile.exists()) {
            String contents = "";
            try {
                contents = FileUtils.readFileToString(mCurrentFile, Charset.defaultCharset());
            } catch (IOException e) {
                e.printStackTrace();
            }
            mEditor.setText(contents);
        }

        mEditor.setOnCompletionItemSelectedListener((window, item) -> {
            Cursor cursor = mEditor.getCursor();
            if (!cursor.isSelected()) {
                window.setCancelShowUp(true);

                int length = window.getLastPrefix().length();
                if (window.getLastPrefix().contains(".")) {
                    length -= window.getLastPrefix().lastIndexOf(".") + 1;
                }
                mEditor.getText().delete(cursor.getLeftLine(), cursor.getLeftColumn() - length, cursor.getLeftLine(), cursor.getLeftColumn());

                window.setSelectedItem(item.commit);
                cursor.onCommitMultilineText(item.commit);

                if (item.commit != null && item.cursorOffset != item.commit.length()) {
                    int delta = (item.commit.length() - item.cursorOffset);
                    int newSel = Math.max(mEditor.getCursor().getLeft() - delta, 0);
                    CharPosition charPosition = mEditor.getCursor().getIndexer().getCharPosition(newSel);
                    mEditor.setSelection(charPosition.line, charPosition.column);
                }

                if (item.item.additionalTextEdits != null) {
                    for (TextEdit edit : item.item.additionalTextEdits) {
                        window.applyTextEdit(edit);
                    }
                }

                if (item.item.action == com.tyron.completion.model.CompletionItem.Kind.IMPORT) {
                    Parser parser = Parser.parseFile(ProjectManager.getInstance().getCurrentProject(), mEditor.getCurrentFile().toPath());
                    ParseTask task = new ParseTask(parser.task, parser.root);

                    boolean samePackage = false;
                    if (!item.item.data.contains(".") //it's either in the same class or it's already imported
                            || task.root.getPackageName().toString().equals(item.item.data.substring(0, item.item.data.lastIndexOf(".")))) {
                        samePackage = true;
                    }

                    if (!samePackage && !CompletionProvider.hasImport(task.root, item.item.data)) {
                        AddImport imp = new AddImport(new File(""), item.item.data);
                        Map<File, TextEdit> edits = imp.getText(task);
                        TextEdit edit = edits.values().iterator().next();
                        window.applyTextEdit(edit);
                    }
                }
                window.setCancelShowUp(false);
            }
            mEditor.postHideCompletionWindow();
        });

        mEditor.setOnLongPressListener((start, end, event) -> {
            Project project = ProjectManager.getInstance().getCurrentProject();
            if (mLanguage instanceof JavaLanguage && project != null) {
                int cursorStart = mEditor.getCursor().getLeft();
                int cursorEnd = mEditor.getCursor().getRight();

                for (LintIssue issue : ((JavaAnalyzer) mLanguage.getAnalyzer()).getDiagnostics()) {
                    CharPosition issueStart = mEditor.getText().getIndexer().getCharPosition(issue.getLocation().getStart().line, issue.getLocation().getStart().column);
                    CharPosition issueEnd = mEditor.getText().getIndexer().getCharPosition(issue.getLocation().getEnd().line, issue.getLocation().getEnd().column);
                    if (issueStart.index <= cursorStart && cursorEnd < issueEnd.index) {
                        new MaterialAlertDialogBuilder(requireContext())
                                .setTitle(issue.getIssue().getId())
                                .setMessage("Severity: " + issue.getSeverity().getDescription() + "\n" +
                                        "" + issue.getIssue().getExplanation(TextFormat.TEXT))
                                .setPositiveButton("OK", null)
                                .show();
                    }
                }
                final Path current = mEditor.getCurrentFile().toPath();
                List<CodeActionList> actions = new CodeActionProvider(CompletionEngine.getInstance().getCompiler(project))
                        .codeActionsForCursor(current, mEditor.getCursor().getLeft());

                mEditor.setOnCreateContextMenuListener((menu, view1, info) -> {

                    for (final CodeActionList action : actions) {
                        if (action.getActions().isEmpty()) {
                            continue;
                        }
                        menu.add(action.getTitle()).setOnMenuItemClickListener(menuItem -> {
                            FileManager.writeFile(mEditor.getCurrentFile(), mEditor.getText().toString());
                            new MaterialAlertDialogBuilder(requireContext())
                                    .setTitle(action.getTitle())
                                    .setItems(action.getActions().stream().map(CodeAction::getTitle).toArray(String[]::new), ((dialogInterface, i) -> {
                                        CodeAction codeAction = action.getActions().get(i);
                                        Map<Path, List<TextEdit>> rewrites = codeAction.getEdits();
                                        List<TextEdit> edits = rewrites.values().iterator().next();
                                        for (TextEdit edit : edits) {
                                            Range range = edit.range;
                                            if (range.start.equals(range.end)) {
                                                mEditor.getText().insert(range.start.line, range.start.column, edit.newText);
                                            } else {
                                                mEditor.getText().replace(range.start.line, range.start.column, range.end.line, range.end.column, edit.newText);
                                            }

                                            int startFormat = mEditor.getText()
                                                    .getCharIndex(range.start.line, range.start.column);
                                            int endFormat = startFormat + edit.newText.length();
                                            mEditor.formatCodeAsync(startFormat, endFormat);
                                        }
                                    })).show();
                            return true;
                        });
                    }
                });
                mEditor.showContextMenu(event.getX(), event.getY());
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        mPreferences.unregisterOnSharedPreferenceChangeListener(this);
    }

    public void save() {
        if (mCurrentFile.exists()) {
            String oldContents = "";
            try {
                oldContents = FileUtils.readFileToString(mCurrentFile, Charset.defaultCharset());
            } catch (IOException e) {
                e.printStackTrace();
            }
            if (oldContents.equals(mEditor.getText().toString())) {
                return;
            }

            Project currentProject = ProjectManager.getInstance().getCurrentProject();
            if (currentProject != null) {
                currentProject.getFileManager()
                        .save(mCurrentFile, mEditor.getText().toString());
            }
        }
    }

    public void setCursorPosition(int line, int column) {
        if (mEditor != null) {
            mEditor.getCursor()
                    .set(line, column);
        }
    }

    public void performShortcut(ShortcutItem item) {
        for (ShortcutAction action : item.actions) {
            if (action.isApplicable(item.kind)) {
                action.apply(mEditor, item);
            }
        }
    }

    public void format() {
        if (mEditor != null) {
            mEditor.formatCodeAsync();
        }
    }

    public void analyze() {
        if (mEditor != null) {
            mEditor.analyze();
        }
    }

    public void preview() {

        final FrameLayout container = new FrameLayout(requireContext());
        if (mEditor != null && mLanguage instanceof LanguageXML) {
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    Instant start = Instant.now();
                    View view = ((LanguageXML) mLanguage).showPreview(requireContext(), container);
                    requireActivity().runOnUiThread(() -> {
                        Toast.makeText(requireContext(), "PreviewTask took: " + Duration.between(start, Instant.now()).toMillis() + " ms.", Toast.LENGTH_SHORT).show();
                        if (view != null) {

                            DisplayMetrics displayMetrics = requireActivity().getResources().getDisplayMetrics();

                            FrameLayout root = new FrameLayout(requireContext());
                            root.addView(view);
                            container.addView(root, new ViewGroup.LayoutParams((int) (displayMetrics.widthPixels * .90), (int) (displayMetrics.heightPixels * .90)));

                            new AlertDialog.Builder(requireContext())
                                    .setView(container)
                                    .show();
                        }
                    });

                } catch (Exception e) {
                    requireActivity().runOnUiThread(() -> {
                        new MaterialAlertDialogBuilder(requireContext())
                                .setTitle("Unable to preview")
                                .setMessage(Log.getStackTraceString(e))
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                    });
                }
            });
        }

    }
}
