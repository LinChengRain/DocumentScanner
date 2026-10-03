package com.documentscanner.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.documentscanner.R;
import com.documentscanner.databinding.ActivityMainBinding;
import com.documentscanner.scanner.api.Scanner;
import com.documentscanner.scanner.api.ScannerEngine;
import com.documentscanner.scanner.api.ScannerSession;
import com.documentscanner.scanner.model.ScanSession;
import com.documentscanner.ui.adapter.SessionAdapter;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页：新扫描入口 + 历史会话列表，并把相机权限流程走顺。
 *
 * <p>这一屏只通过 {@code com.documentscanner.scanner.api} 碰扫描组件，加上
 * {@link ScanSession.Info} 这一个数据类型；不起裸 Intent、不调 {@code ScanSession.startNew}。
 * 两者必须成对（先切会话再起屏），散在宿主里就变成「漏写前半句时接着上一份会话写」这种
 * 只有真机能发现的 bug。
 */
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

        binding.tvEngine.setText(getString(R.string.main_engine, ScannerEngine.version()));
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
        List<ScanSession.Info> history = ScannerSession.list(this);
        sessions.clear();
        sessions.addAll(history);
        adapter.notifyDataSetChanged();
        boolean hasHistory = !sessions.isEmpty();
        binding.tvHistoryTitle.setVisibility(hasHistory ? View.VISIBLE : View.GONE);
        binding.rvSessions.setVisibility(hasHistory ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onSessionClick(ScanSession.Info info) {
        Scanner.openPages(this, info.sessionId);
    }

    @Override
    public void onSessionMenu(ScanSession.Info info) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.main_session_delete_title)
                .setMessage(getString(R.string.main_session_delete_body,
                        info.title == null ? getString(R.string.main_session_untitled) : info.title))
                .setNegativeButton(R.string.scanner_cancel, null)
                .setPositiveButton(R.string.scanner_delete, (dialog, which) -> {
                    ScannerSession.delete(this, info.sessionId);
                    refreshHistory();
                    Toast.makeText(this,
                                    getString(R.string.main_session_deleted, info.sessionId),
                                    Toast.LENGTH_SHORT)
                            .show();
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
                    .setTitle(R.string.scanner_permission_camera_title)
                    .setMessage(R.string.scanner_permission_camera_body)
                    .setNegativeButton(R.string.scanner_cancel, null)
                    .setPositiveButton(R.string.scanner_permission_grant,
                            (dialog, which) -> cameraPermission.launch(Manifest.permission.CAMERA))
                    .show();
            return;
        }
        cameraPermission.launch(Manifest.permission.CAMERA);
    }

    private void openScanner() {
        Scanner.startNewScan(this);
    }

    private void showPermissionRationale() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.scanner_permission_camera_title)
                .setMessage(R.string.scanner_permission_denied)
                .setNegativeButton(R.string.scanner_cancel, null)
                .setPositiveButton(R.string.scanner_permission_settings, (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", getPackageName(), null));
                    if (intent.resolveActivity(getPackageManager()) != null) {
                        startActivity(intent);
                    }
                })
                .show();
    }
}
