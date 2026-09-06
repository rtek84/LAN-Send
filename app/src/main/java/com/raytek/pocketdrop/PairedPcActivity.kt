package com.raytek.pocketdrop

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class PairedPcActivity : AppCompatActivity() {
    private lateinit var listContainer: LinearLayout
    private lateinit var countLabel: TextView
    private lateinit var addButton: Button
    private var baseBottomPadding = 0

    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        val value = result.contents ?: return@registerForActivityResult
        val parts = value.split('|')
        if (parts.size < 4 || parts[0] != "pocketdrop") {
            showMessage("That is not a LAN Send QR code")
            return@registerForActivityResult
        }

        val server = parts[1]
        val token = parts[2]
        val deviceId = parts[3]
        val existing = PairedPcStore.records(this).any { it.deviceId == deviceId }
        if (!existing && PairedPcStore.records(this).size >= PairedPcStore.MAX_REMEMBERED_PCS) {
            showMessage("LAN Send can remember up to 3 PCs at a time. Forget one of the remembered PCs before adding another.")
            return@registerForActivityResult
        }

        val resultRecord = PairedPcStore.addOrActivate(this, server, token, deviceId)
        if (resultRecord == null) {
            showMessage("Could not save this PC. Please scan the QR code again.")
            return@registerForActivityResult
        }

        PhoneRegistration.registerAsync(this, resultRecord.record)
        setResult(Activity.RESULT_OK)
        refreshList()
        showMessage(if (resultRecord.added) "PC added and selected" else "PC selected")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Remembered PCs"
        setResult(Activity.RESULT_OK)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.pocket_surface))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = dp(3).toFloat()
            setBackgroundColor(getColor(R.color.pocket_surface))
            setPadding(dp(8), dp(10), dp(20), dp(12))
        }
        header.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = ColorStateList.valueOf(getColor(R.color.pocket_text_soft))
            contentDescription = "Back"
            val selectableBackground = TypedValue()
            if (theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, selectableBackground, true)) {
                setBackgroundResource(selectableBackground.resourceId)
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(TextView(this).apply {
            text = "Remembered PCs"
            textSize = 25f
            setTextColor(getColor(R.color.pocket_text))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }
        baseBottomPadding = dp(16)

        body.addView(TextView(this).apply {
            text = "Choose the PC LAN Send should use. Up to 3 PCs can be remembered at one time."
            textSize = 13f
            setTextColor(getColor(R.color.pocket_muted))
            setPadding(0, 0, 0, dp(10))
        })

        countLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.pocket_muted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        body.addView(countLabel)

        val scroll = ScrollView(this).apply {
            clipToPadding = false
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(8))
        }
        scroll.addView(listContainer)
        body.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        addButton = Button(this).apply {
            text = "Add PC"
            isAllCaps = false
            setOnClickListener { scanAnotherPc() }
        }
        body.addView(addButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
            topMargin = dp(8)
        })

        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            header.setPadding(dp(8), dp(10) + systemBars.top, dp(20), dp(12))
            body.setPadding(dp(20), dp(20), dp(20), baseBottomPadding + systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        refreshList()
    }

    private fun scanAnotherPc() {
        val records = PairedPcStore.records(this)
        if (records.size >= PairedPcStore.MAX_REMEMBERED_PCS) {
            showMessage("3 of 3 PCs are already remembered. Forget one before adding another.")
            return
        }
        qrScanner.launch(ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan the LAN Send QR code on the PC")
            setBeepEnabled(false)
            setOrientationLocked(true)
        })
    }

    private fun refreshList() {
        val records = PairedPcStore.records(this)
        val activeId = PairedPcStore.activeRecord(this)?.recordId
        countLabel.text = "${records.size} of ${PairedPcStore.MAX_REMEMBERED_PCS} PCs remembered"
        addButton.isEnabled = records.size < PairedPcStore.MAX_REMEMBERED_PCS
        listContainer.removeAllViews()

        if (records.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "No PCs remembered yet"
                textSize = 15f
                setTextColor(getColor(R.color.pocket_text_soft))
                gravity = Gravity.CENTER
                setPadding(0, 48, 0, 48)
            })
            return
        }

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        records.forEach { record ->
            val selected = record.recordId == activeId
            val card = MaterialCardView(this).apply {
                radius = dp(16).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(getColor(R.color.pocket_card))
                strokeColor = getColor(if (selected) R.color.pocket_blue else R.color.pocket_border)
                strokeWidth = dp(if (selected) 2 else 1)
                isClickable = true
                isFocusable = true
                setOnClickListener { showPcActions(record) }
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(12), dp(14))
            }

            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            details.addView(TextView(this).apply {
                text = record.name
                textSize = 16f
                setTextColor(getColor(R.color.pocket_text))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            details.addView(TextView(this).apply {
                text = record.server
                textSize = 12f
                setTextColor(getColor(R.color.pocket_muted))
                setPadding(0, dp(4), 0, 0)
            })
            if (selected) {
                details.addView(TextView(this).apply {
                    text = "Selected"
                    textSize = 12f
                    setTextColor(getColor(R.color.pocket_blue))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, dp(7), 0, 0)
                })
            }
            row.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = "Manage"
                isAllCaps = false
                setTextColor(getColor(R.color.pocket_blue))
                minWidth = 0
                setPadding(dp(10), 0, dp(10), 0)
                setOnClickListener { showPcActions(record) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))

            card.addView(row)
            listContainer.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })
        }
    }

    private fun showPcActions(record: PairedPcRecord) {
        val active = PairedPcStore.activeRecord(this)?.recordId == record.recordId
        val actions = mutableListOf<String>()
        if (!active) actions += "Use this PC"
        actions += "Rename"
        actions += "Forget"

        AlertDialog.Builder(this)
            .setTitle(record.name)
            .setMessage(record.server)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "Use this PC" -> {
                        PairedPcStore.select(this, record.recordId)?.let {
                            PhoneRegistration.registerAsync(this, it)
                        }
                        setResult(Activity.RESULT_OK)
                        refreshList()
                    }
                    "Rename" -> showRenameDialog(record)
                    "Forget" -> confirmForget(record)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameDialog(record: PairedPcRecord) {
        val input = EditText(this).apply {
            setText(record.name)
            selectAll()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        AlertDialog.Builder(this)
            .setTitle("Rename PC")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                if (PairedPcStore.rename(this, record.recordId, input.text.toString())) {
                    setResult(Activity.RESULT_OK)
                    refreshList()
                }
            }
            .show()
    }

    private fun confirmForget(record: PairedPcRecord) {
        AlertDialog.Builder(this)
            .setTitle("Forget ${record.name}?")
            .setMessage("You can pair this PC again later by scanning its QR code. Transferred files will not be deleted.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Forget") { _, _ ->
                PairedPcStore.forget(this, record.recordId)?.let {
                    PhoneRegistration.registerAsync(this, it)
                }
                setResult(Activity.RESULT_OK)
                refreshList()
            }
            .show()
    }

    private fun showMessage(message: String) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}
