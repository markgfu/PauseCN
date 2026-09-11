package app.pausecn.accessibility

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.InputFilter
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import app.pausecn.domain.ContinueReason
import app.pausecn.domain.interventionPurpose

/** No persistence or network here; only the final explicit submit leaves this view. */
internal class ReasonEntryView(
    context: Context,
    choices: List<String>,
    private val onSubmit: (String) -> Unit,
) : LinearLayout(context) {
    private var selected: String? = null
    private var submitted = false
    private val input = EditText(context).apply {
        hint = "补充说明，或直接写自己的理由"
        contentDescription = "继续理由输入，最多80个字"
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        isSaveEnabled = false
        filters = arrayOf(InputFilter.LengthFilter(160))
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setTextColor(Color.rgb(23, 33, 27))
        textSize = 16f
    }
    private val feedback = TextView(context).apply { textSize = 14f; setTextColor(Color.rgb(87, 98, 91)) }
    private val confirm = Button(context).apply { text = "确认理由并继续"; isAllCaps = false; minimumHeight = dp(52) }
    private val choiceButtons = mutableListOf<Pair<String, Button>>()

    init {
        orientation = VERTICAL
        isSaveEnabled = false
        choices.filter(ContinueReason::isValid).distinct().take(8).forEach { choice ->
            val button = Button(context).apply {
                text = choice
                isAllCaps = false
                minimumHeight = dp(48)
                textSize = 15f
                setTextColor(Color.rgb(23, 33, 27))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(226, 234, 224)); cornerRadius = dp(16).toFloat()
                }
                setOnClickListener {
                    if (selected == choice) {
                        selected = null
                    } else {
                        selected = choice.takeIf { interventionPurpose(it) != null }
                        input.setText(if (selected == null) choice else "")
                    }
                    update()
                }
            }
            choiceButtons += choice to button
            addView(button, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(3), 0, dp(3))
            })
        }
        addView(input, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(feedback, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(confirm, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(TextView(context).apply {
            text = "确认继续后，理由随记录保存在本机，并用于下次排序。可在设置中删除；不会自动发给 AI。"
            textSize = 12f
        })
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = update()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        confirm.setOnClickListener {
            val reason = ContinueReason.compose(selected, input.text.toString())
            if (reason != null && !submitted) {
                submitted = true
                confirm.isEnabled = false
                onSubmit(reason)
            }
        }
        update()
    }

    private fun update() {
        val reason = ContinueReason.compose(selected, input.text.toString())
        choiceButtons.forEach { (choice, button) ->
            button.text = if (choice == reason || choice == selected) "✓ $choice" else choice
        }
        feedback.text = when {
            ContinueReason.isCasual(reason) -> "没有明确目的也可以继续，请再确认这是现在想做的事。"
            reason == null && (input.text.isNotEmpty() || selected != null) -> "理由合计最多80个字，不能含换行或控制字符。"
            else -> "选一个理由，可补充说明；也可以只输入。合计最多80个字。"
        }
        confirm.text = if (ContinueReason.isCasual(reason)) "仍然继续" else "确认理由并继续"
        confirm.isEnabled = reason != null && !submitted
    }

    override fun onDetachedFromWindow() {
        selected = null
        input.setText("")
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
