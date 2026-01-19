package com.certis.screeneye

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.certis.screeneye.data.AppDatabase
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LogActivity : AppCompatActivity() {

    private lateinit var logsRecyclerView: RecyclerView
    private lateinit var emptyLogsText: View
    private val adapter = LogAdapter()
    private lateinit var logExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        logsRecyclerView = findViewById(R.id.logsRecyclerView)
        emptyLogsText = findViewById(R.id.emptyLogsText)
        logsRecyclerView.layoutManager = LinearLayoutManager(this)
        logsRecyclerView.adapter = adapter

        logExecutor = Executors.newSingleThreadExecutor()
        loadLogs()
    }

    override fun onDestroy() {
        super.onDestroy()
        logExecutor.shutdown()
    }

    private fun loadLogs() {
        val dao = AppDatabase.getInstance(this).logEventDao()
        logExecutor.execute {
            val events = dao.recent(200)
            runOnUiThread {
                adapter.submit(events)
                emptyLogsText.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
}
