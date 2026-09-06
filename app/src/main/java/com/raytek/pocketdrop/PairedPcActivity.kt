package com.raytek.pocketdrop

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class PairedPcActivity : AppCompatActivity() {
    private lateinit var listContainer: LinearLayout
    private lateinit var countLabel: TextView
    private lateinit var addButton: Button

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

        setResult(Activity.RESULT_OK)
        refreshList()
        showMessage(if (resultRecord.added) "PC added and selected" else "PC selected")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Remembered PCs"
        setResult(Activity.RESULT_OK)

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(getColor(R.color.pocket_surface))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(Button(this).apply {
            text = "Back"
            isAllCaps = false
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(TextView(this).apply {
            text = "Remembered PCs"
            textSize = 24f
            setTextColor(getColor(R.color.pocket_text))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        root.addView(TextView(this).apply {
            text = "LAN Send remembers up to 3 PCs. Transfers still go to one selected PC at a time."
            textSize = 13f
            setTextColor(getColor(R.color.pocket_muted))
            setPadding(0, dp(14), 0, dp(12))
        })

        countLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.pocket_muted))
        }
        root.addView(countLabel)

        val scroll = ScrollView(this)
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        scroll.addView(listContainer)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        addButton = Button(this).apply {
            text = "Add PC"
            isAllCaps = false
            setOnClickListener { scanAnotherPc() }
        }
        root.addView(addButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        setContentView(root)
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
                setPadding(0, 24, 0, 24)
            })
            return
        }

        val density = resources.displayMetrics.density
        val gap = (10 * density).toInt()
        records.forEach { record ->
            val button = Button(this).apply {
                text = if (record.recordId == activeId) {
                    "${record.name}  -  Selected\n${record.server}"
                } else {
                    "${record.name}\n${record.server}"
                }
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(18, 12, 18, 12)
                setOnClickListener { showPcActions(record) }
            }
            listContainer.addView(button, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = gap })
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
                        PairedPcStore.select(this, record.recordId)
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
                PairedPcStore.forget(this, record.recordId)
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
