package com.tyron.code.ui.editor.impl.text.rosemoe;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.math.MathUtils;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.snackbar.Snackbar;
import com.tyron.actions.ActionManager;
import com.tyron.actions.ActionPlaces;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.DataContext;
import com.tyron.actions.util.DataContextUtils;
import com.tyron.builder.compiler.manifest.xml.XmlFormatPreferences;
import com.tyron.builder.compiler.manifest.xml.XmlFormatStyle;
import com.tyron.builder.compiler.manifest.xml.XmlPrettyPrinter;
import com.tyron.builder.log.LogViewModel;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.api.Module;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.R;
import com.tyron.code.ui.editor.CodeAssistCompletionAdapter;
import com.tyron.code.ui.editor.CodeAssistCompletionLayout;
import com.tyron.code.ui.editor.EditorViewModel;
import com.tyron.code.ui.editor.Savable;
import com.tyron.code.ui.editor.impl.FileEditorManagerImpl;
import com.tyron.code.ui.editor.language.LanguageManager;
import com.tyron.code.ui.editor.language.java.JavaLanguage;
import com.tyron.code.ui.editor.language.kotlin.KotlinLanguage;
import com.tyron.code.ui.editor.language.xml.LanguageXML;
import com.tyron.code.ui.editor.shortcuts.ShortcutAction;
import com.tyron.code.ui.editor.shortcuts.ShortcutItem;
import com.tyron.code.ui.layoutEditor.LayoutEditorFragment;
import com.tyron.code.ui.main.MainViewModel;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.common.SharedPreferenceKeys;
import com.tyron.common.util.AndroidUtilities;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.java.JavaCompilerProvider;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.compiler.ParseTask;
import com.tyron.completion.java.compiler.Parser;
import com.tyron.completion.java.action.CommonJavaContextKeys;
import com.tyron.completion.java.action.FindCurrentPath;
import com.tyron.completion.java.provider.CompletionEngine;
import com.tyron.completion.java.rewrite.AddImport;
import com.tyron.completion.java.util.ActionUtil;
import com.tyron.completion.java.util.DiagnosticUtil;
import com.tyron.completion.java.util.JavaDataContextUtil;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.TextEdit;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.editor.CharPosition;
import com.tyron.editor.Editor;
import com.tyron.fileeditor.api.FileEditorManager;

import org.apache.commons.io.FileUtils;
import org.openjdk.com.sun.org.apache.xpath.internal.operations.Mod;
import org.openjdk.source.util.TreePath;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.event.EventReceiver;
import io.github.rosemoe.sora.event.LongPressEvent;
import io.github.rosemoe.sora.event.Unsubscribe;
import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.text.Cursor;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.DirectAccessProps;
import io.github.rosemoe.sora.widget.component.DefaultCompletionLayout;
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula;
import io.github.rosemoe.sora2.text.EditorUtil;

@SuppressWarnings("FieldCanBeLocal")
public class CodeEditorFragment extends Fragment implements Savable,
        SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String EDITOR_LEFT_LINE_KEY = "line";
    private static final String EDITOR_LEFT_COLUMN_KEY = "column";
    private static final String EDITOR_RIGHT_LINE_KEY = "rightLine";
    private static final String EDITOR_RIGHT_COLUMN_KEY = "rightColumn";

    private CodeEditorView mEditor;

    private Language mLanguage;
    private File mCurrentFile = new File("");
    private MainViewModel mMainViewModel;
    private SharedPreferences mPreferences;

    private boolean mCanSave;

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
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        if (mCanSave) {
            if (ProjectManager.getInstance().getCurrentProject() != null) {
                ProjectManager.getInstance().getCurrentProject()
                        .getModule(mCurrentFile)
                        .getFileManager()
                        .setSnapshotContent(mCurrentFile, mEditor.getText().toString());
            }
        }

        Cursor cursor = mEditor.getCursor();
        outState.putInt(EDITOR_LEFT_LINE_KEY, cursor.getLeftLine());
        outState.putInt(EDITOR_LEFT_COLUMN_KEY, cursor.getLeftColumn());
        outState.putInt(EDITOR_RIGHT_LINE_KEY, cursor.getRightLine());
        outState.putInt(EDITOR_RIGHT_COLUMN_KEY, cursor.getRightColumn());
    }

    @Override
    public void onResume() {
        super.onResume();

        if (!CompletionEngine.isIndexing()) {
            analyze();
        }

        if (BottomSheetBehavior.STATE_HIDDEN == mMainViewModel.getBottomSheetState().getValue()) {
            mMainViewModel.setBottomSheetState(BottomSheetBehavior.STATE_COLLAPSED);
        }

        Project currentProject = ProjectManager.getInstance().getCurrentProject();
        if (currentProject != null) {
            Module module = currentProject.getModule(mCurrentFile);
            Optional<CharSequence> fileContent =
                    module.getFileManager().getFileContent(mCurrentFile);
            if (fileContent.isPresent()) {
                CharSequence content = fileContent.get();
                if (!content.equals(mEditor.getText())) {
                    int line = mEditor.getCursor().getLeftLine();
                    int column = mEditor.getCursor().getLeftColumn();

                    int targetX = mEditor.getOffsetX();
                    int targetY = mEditor.getOffsetY();

                    mEditor.setText(content);

                    mEditor.setSelection(line, column, false);

                    mEditor.getScroller().startScroll(mEditor.getOffsetX(), mEditor.getOffsetY(),
                            targetX - mEditor.getOffsetX(), targetY - mEditor.getOffsetY(), 0);
                }
            }
        }
    }


    public void hideEditorWindows() {
        mEditor.hideAutoCompleteWindow();
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        mPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext());

        View root = inflater.inflate(R.layout.code_editor_fragment, container, false);

        mEditor = root.findViewById(R.id.code_editor);
        configure(mEditor.getProps());
        mEditor.setEditorLanguage(mLanguage = LanguageManager.getInstance().get(mEditor, mCurrentFile));
        SchemeDarcula scheme = new SchemeDarcula();
        scheme.setColor(EditorColorScheme.HTML_TAG, 0xFFF0C56C);
        scheme.setColor(EditorColorScheme.ATTRIBUTE_NAME, 0xff9876AA);
        scheme.setColor(EditorColorScheme.AUTO_COMP_PANEL_BG, 0xff2b2b2b);
        scheme.setColor(EditorColorScheme.AUTO_COMP_PANEL_CORNER, 0xff575757);
        mEditor.setColorScheme(scheme);
        mEditor.setTextSize(Integer.parseInt(mPreferences.getString(SharedPreferenceKeys.FONT_SIZE, "12")));
        mEditor.openFile(mCurrentFile);
        mEditor.getComponent(EditorAutoCompletion.class).setLayout(new CodeAssistCompletionLayout());
        mEditor.setAutoCompletionItemAdapter(new CodeAssistCompletionAdapter());
        mEditor.setTypefaceText(ResourcesCompat.getFont(requireContext(),
                R.font.jetbrains_mono_regular));
        mEditor.setLigatureEnabled(true);
        mEditor.setHighlightCurrentBlock(true);
        mEditor.setEdgeEffectColor(Color.TRANSPARENT);
        mEditor.setWordwrap(mPreferences.getBoolean(SharedPreferenceKeys.EDITOR_WORDWRAP, false));
        mEditor.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);

        View topView = root.findViewById(R.id.top_view);
        EditorViewModel viewModel = new ViewModelProvider(this).get(EditorViewModel.class);
        viewModel.getAnalyzeState().observe(getViewLifecycleOwner(), analyzing -> {
            if (analyzing) {
                topView.setVisibility(View.VISIBLE);
            } else {
                topView.setVisibility(View.GONE);
            }
        });
        mEditor.setViewModel(viewModel);

        if (mPreferences.getBoolean(SharedPreferenceKeys.KEYBOARD_ENABLE_SUGGESTIONS, false)) {
            mEditor.setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE);
        } else {
            mEditor.setInputType(EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS | EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE | EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        }
        mPreferences.registerOnSharedPreferenceChangeListener(this);
        return root;
    }

    private void configure(DirectAccessProps props) {
        props.overScrollEnabled = false;
        props.allowFullscreen = false;
        props.deleteEmptyLineFast = false;
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
                break;
            case SharedPreferenceKeys.EDITOR_WORDWRAP:
                mEditor.setWordwrap(pref.getBoolean(key, false));
                break;
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Project currentProject = ProjectManager.getInstance().getCurrentProject();
        readFile(currentProject, savedInstanceState);

        mEditor.setOnCreateContextMenuListener((menu, view1, contextMenuInfo) -> {
            menu.clear();

            DataContext dataContext = DataContextUtils.getDataContext(view1);
            dataContext.putData(CommonDataKeys.PROJECT, currentProject);
            dataContext.putData(CommonDataKeys.FILE_EDITOR_KEY, mMainViewModel.getCurrentFileEditor());
            dataContext.putData(CommonDataKeys.FILE, mCurrentFile);
            dataContext.putData(CommonDataKeys.EDITOR, mEditor);

            if (currentProject != null && mLanguage instanceof JavaLanguage) {
                JavaDataContextUtil.addEditorKeys(dataContext,
                        currentProject, mCurrentFile, mEditor.getCursor().getLeft());
            }

            DiagnosticWrapper diagnosticWrapper =
                    DiagnosticUtil.getDiagnosticWrapper(mEditor.getDiagnostics(),
                            mEditor.getCursor().getLeft());
            if (diagnosticWrapper == null && mLanguage instanceof LanguageXML) {
                diagnosticWrapper = DiagnosticUtil.getXmlDiagnosticWrapper(mEditor.getDiagnostics(),
                                mEditor.getCursor().getLeftLine());
            }
            dataContext.putData(CommonDataKeys.DIAGNOSTIC, diagnosticWrapper);

            ActionManager.getInstance().fillMenu(dataContext, menu, ActionPlaces.EDITOR, true,
                    false);
        });
        mEditor.subscribeEvent(LongPressEvent.class, (event, unsubscribe) -> {
            MotionEvent e = event.getCausingEvent();
            save();
            // wait for the cursor to move
            ProgressManager.getInstance().runLater(() -> {
                Cursor cursor = mEditor.getCursor();
                if (cursor.isSelected()) {
                    int index = mEditor.getCharIndex(event.getLine(), event.getColumn());
                    int cursorLeft = cursor.getLeft();
                    int cursorRight = cursor.getRight();
                    char c = mEditor.getText().charAt(index);
                    if (Character.isWhitespace(c)) {
                        mEditor.setSelection(event.getLine(), event.getColumn());
                    } else if (index < cursorLeft || index > cursorRight) {
                        EditorUtil.selectWord(mEditor, event.getLine(), event.getColumn());
                    }
                }
                mEditor.showContextMenu(e.getX(), e.getY());
            });
        });
        mEditor.subscribeEvent(ContentChangeEvent.class, (event, unsubscribe) ->
                updateFile(event.getEditor().getText()));

        LogViewModel logViewModel =
                new ViewModelProvider(requireActivity()).get(LogViewModel.class);
        mEditor.setDiagnosticsListener(diagnostics ->
                ProgressManager.getInstance().runLater(() ->
                        logViewModel.updateLogs(LogViewModel.DEBUG, diagnostics)));

        getChildFragmentManager().setFragmentResultListener(LayoutEditorFragment.KEY_SAVE,
                getViewLifecycleOwner(), ((requestKey, result) -> {
            String xml = result.getString("text", mEditor.getText().toString());
            xml = XmlPrettyPrinter.prettyPrint(xml, XmlFormatPreferences.defaults(),
                    XmlFormatStyle.LAYOUT, "\n");
            mEditor.setText(xml);
        }));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mEditor.setEditorLanguage(null);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (ProjectManager.getInstance().getCurrentProject() != null) {
            ProjectManager.getInstance().getCurrentProject().getModule(mCurrentFile).getFileManager().closeFileForSnapshot(mCurrentFile);
        }
        mPreferences.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();

        hideEditorWindows();

        if (mCanSave) {
            if (ProjectManager.getInstance().getCurrentProject() != null) {
                ProjectManager.getInstance().getCurrentProject().getModule(mCurrentFile).getFileManager().setSnapshotContent(mCurrentFile, mEditor.getText().toString());
            } else {
                ProgressManager.getInstance().runNonCancelableAsync(() -> {
                    try {
                        FileUtils.writeStringToFile(mCurrentFile, mEditor.getText().toString(),
                                StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        // ignored
                    }
                });
            }
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();

        mEditor.setBackgroundAnalysisEnabled(false);
    }

    @Override
    public void save() {
        if (!mCanSave) {
            return;
        }
        if (mCurrentFile.exists()) {
            ProgressManager.getInstance().runNonCancelableAsync(() -> {
                String oldContents = "";
                try {
                    oldContents = FileUtils.readFileToString(mCurrentFile, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                if (oldContents.equals(mEditor.getText().toString())) {
                    return;
                }

                try {
                    FileUtils.writeStringToFile(mCurrentFile, mEditor.getText().toString(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    // ignored
                }
            });
        }
    }

    private void readFile(Project currentProject, @Nullable Bundle savedInstanceState) {
        ProgressManager.getInstance().runNonCancelableAsync(() -> {
            Module module;
            if (currentProject != null) {
                module = currentProject.getModule(mCurrentFile);
            } else {
                module = null;
            }
            if (mCurrentFile.exists()) {
                String text;
                try {
                    text = FileUtils.readFileToString(mCurrentFile, StandardCharsets.UTF_8);
                    mCanSave = true;
                } catch (IOException e) {
                    text = "File does not exist: " + e.getMessage();
                    mCanSave = false;
                }
                if (module != null) {
                    module.getFileManager().openFileForSnapshot(mCurrentFile, text);
                }

                String finalText = text;
                ProgressManager.getInstance().runLater(() -> {
                    checkCanSave();
                    mEditor.setText(finalText);

                    if (savedInstanceState != null) {
                        restoreState(savedInstanceState);
                    }
                });
            } else {
                mCanSave = false;
            }
        });
    }

    private void checkCanSave() {
        if (!mCanSave) {
            Snackbar snackbar = Snackbar.make(mEditor, R.string.editor_error_file,
                    Snackbar.LENGTH_INDEFINITE).setAction(R.string.menu_close,
                    v -> FileEditorManagerImpl.getInstance().closeFile(mCurrentFile));
            ViewGroup snackbarView = (ViewGroup) snackbar.getView();
            AndroidUtilities.setMargins(snackbarView,
                    0, 0, 0,50);
            snackbar.show();
        }
    }

    private void restoreState(@NonNull Bundle savedInstanceState) {
        int leftLine = savedInstanceState.getInt(EDITOR_LEFT_LINE_KEY, 0);
        int leftColumn = savedInstanceState.getInt(EDITOR_LEFT_COLUMN_KEY, 0);
        int rightLine = savedInstanceState.getInt(EDITOR_RIGHT_LINE_KEY, 0);
        int rightColumn = savedInstanceState.getInt(EDITOR_RIGHT_COLUMN_KEY, 0);

        if (leftLine != rightLine && leftColumn != rightColumn) {
            mEditor.setSelectionRegion(leftLine, leftColumn, rightLine, rightColumn, true);
        } else {
            mEditor.setSelection(leftLine, leftColumn);
        }
    }

    private void updateFile(CharSequence contents) {
        Project project = ProjectManager.getInstance().getCurrentProject();
        if (project == null) {
            return;
        }
        Module module = project.getModule(mCurrentFile);
        if (module != null) {
            module.getFileManager().setSnapshotContent(mCurrentFile, contents.toString());
        }
    }

    public Editor getEditor() {
        return mEditor;
    }

    /**
     * Undo the text in the editor if possible, if not the call is ignored
     */
    public void undo() {
        if (mEditor == null) {
            return;
        }
        if (mEditor.canUndo()) {
            mEditor.undo();
        }
    }

    /**
     * Redo the text in the editor if possible, if not the call is ignored
     */
    public void redo() {
        if (mEditor == null) {
            return;
        }
        if (mEditor.canRedo()) {
            mEditor.redo();
        }
    }

    /**
     * Sets the position of the cursor in the editor
     *
     * @param line   zero-based line.
     * @param column zero-based column.
     */
    public void setCursorPosition(int line, int column) {
        if (mEditor != null) {
            mEditor.getCursor().set(line, column);
        }
    }

    /**
     * Perform a shortcut item to the editor
     *
     * @param item the item to be performed
     */
    public void performShortcut(ShortcutItem item) {
        if (mEditor == null) {
            return;
        }
        for (ShortcutAction action : item.actions) {
            action.apply(mEditor, item);
        }
    }

    public void format() {
        if (mEditor != null) {
//            if (mEditor.getCursor().isSelected()) {
//                if (mLanguage instanceof JavaLanguage) {
//                    Cursor cursor = mEditor.getCursor();
//                    CharSequence format = mLanguage.format(mEditor.getText(), cursor.getLeft(),
//                            cursor.getRight());
//                    mEditor.setText(format);
//                    return;
//                }
//            }
            mEditor.formatCodeAsync();
        }
    }

    /**
     * Notifies the editor to analyze and highlight the current text
     */
    public void analyze() {
        if (mEditor != null) {
            mEditor.rerunAnalysis();
        }
    }
}
