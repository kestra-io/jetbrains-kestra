package io.kestra.jetbrains.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionConfidence
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ThreeState
import org.jetbrains.yaml.psi.YAMLFile

private fun insideExpression(editor: Editor, offset: Int): Boolean {
    val document = editor.document
    val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
    return ExpressionQuery.of(document.getText(TextRange(lineStart, offset))) != null
}

// Opens completion after {{, a dot, or a pipe inside an expression.
class PebbleTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (file !is YAMLFile || charTyped !in "{.|") {
            return Result.CONTINUE
        }
        val offset = editor.caretModel.offset
        val document = editor.document
        val opened = charTyped == '{' && offset > 0 && document.charsSequence[offset - 1] == '{'
        if (opened || (charTyped != '{' && insideExpression(editor, offset))) {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
            return Result.STOP
        }
        return Result.CONTINUE
    }
}

// YAML plain scalars are free text, where the platform would skip the auto-popup.
class PebbleCompletionConfidence : CompletionConfidence() {
    override fun shouldSkipAutopopup(editor: Editor, contextElement: PsiElement, psiFile: PsiFile, offset: Int): ThreeState =
        if (psiFile is YAMLFile && insideExpression(editor, offset)) ThreeState.NO else ThreeState.UNSURE
}
