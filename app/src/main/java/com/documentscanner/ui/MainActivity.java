package com.documentscanner.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.documentscanner.R;
import com.documentscanner.cv.Cv;
import com.documentscanner.databinding.ActivityMainBinding;
import com.documentscanner.model.ScanSession;
import com.documentscanner.ui.adapter.SessionAdapter;
import com.documentscanner.util.Ui;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/** 首页：新扫描入口 + 历史会话列表，并把相机权限流程走顺。 */
public class MainActivity extends AppCompatActivity implements SessionAdapter.Listener {

    private ActivityMainBinding binding;
    private final List<ScanSession.Info> sessions = new ArrayList<>();
    private SessionAdapter adapter;
    private ActivityResultLauncher<String> cameraPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.tvEngine.setText(getString(R.string.main_engine, Cv.version()));
        binding.btnStart.setOnClickListener(v -> startScanning());

        adapter = new SessionAdapter(sessions, this);
        binding.rvSessions.setLayoutManager(new LinearLayoutManager(this));
        binding.rvSessions.setAdapter(adapter);

        cameraPermission = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        openScanner();
                    } else {
                        showPermissionRationale();
                    }
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshHistory();
    }

    private void refreshHistory() {
        List<ScanSession.Info> history = ScanSession.history(this);
        sessions.clear();
        sessions.addAll(history);
        adapter.notifyDataSetChanged();
        boolean hasHistory = !sessions.isEmpty();
        binding.tvHistoryTitle.setVisibility(hasHistory ? View.VISIBLE : View.GONE);
        binding.rvSessions.setVisibility(hasHistory ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onSessionClick(ScanSession.Info info) {
        ScanSession.open(this, info.sessionId);
        startActivity(new Intent(this, PageListActivity.class));
    }

    @Override
    public void onSessionMenu(ScanSession.Info info) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.main_session_delete_title)
                .setMessage(getString(R.string.main_session_delete_body,
                        info.title == null ? getString(R.string.main_session_untitled) : info.title))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    ScanSession.deleteSession(this, info.sessionId);
                    refreshHistory();
                    Ui.toast(this, getString(R.string.main_session_deleted, info.sessionId));
                })
                .show();
    }

    private void startScanning() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            openScanner();
            return;
        }
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.permission_camera_title)
                    .setMessage(R.string.permission_camera_body)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.permission_grant,
                            (dialog, which) -> cameraPermission.launch(Manifest.permission.CAMERA))
                    .show();
            return;
        }
        cameraPermission.launch(Manifest.permission.CAMERA);
    }

    private void openScanner() {
        ScanSession.startNew(this);
        startActivity(new Intent(this, ScanActivity.class));
    }

    private void showPermissionRationale() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.permission_camera_title)
                .setMessage(R.string.permission_denied)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.permission_settings, (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", getPackageName(), null));
                    if (intent.resolveActivity(getPackageManager()) != null) {
                        startActivity(intent);
                    }
                })
                .show();
    }
}
