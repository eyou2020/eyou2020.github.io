package com.autoclean.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.autoclean.app.R
import com.autoclean.app.databinding.ActivityMainBinding
import com.autoclean.app.model.CleanSchedule
import com.autoclean.app.service.NotificationHelper
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: ScheduleAdapter

    companion object {
        const val EXTRA_SCHEDULE = "extra_schedule"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        NotificationHelper.createNotificationChannel(this)

        setupRecyclerView()
        observeViewModel()

        binding.fabAdd.setOnClickListener {
            startActivity(Intent(this, AddScheduleActivity::class.java))
        }
    }

    private fun setupRecyclerView() {
        adapter = ScheduleAdapter(
            onToggle = { schedule, enabled -> viewModel.toggleSchedule(schedule, enabled) },
            onEdit = { schedule ->
                val intent = Intent(this, AddScheduleActivity::class.java)
                intent.putExtra(EXTRA_SCHEDULE, schedule)
                startActivity(intent)
            },
            onDelete = { schedule -> showDeleteDialog(schedule) },
            onRunNow = { schedule ->
                viewModel.runNow(schedule)
                Snackbar.make(binding.root, getString(R.string.run_now_started), Snackbar.LENGTH_SHORT).show()
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        val swipeCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val schedule = adapter.currentList[viewHolder.adapterPosition]
                showDeleteDialog(schedule)
                adapter.notifyItemChanged(viewHolder.adapterPosition)
            }
        }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.recyclerView)
    }

    private fun observeViewModel() {
        viewModel.schedules.observe(this) { schedules ->
            adapter.submitList(schedules)
            binding.tvEmpty.visibility = if (schedules.isEmpty())
                android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private fun showDeleteDialog(schedule: CleanSchedule) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(getString(R.string.delete_confirm_message, schedule.folderName))
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteSchedule(schedule) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_about -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.app_name)
                    .setMessage(R.string.about_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
