package com.termux.app.ai;

import android.os.Handler;
import android.os.Looper;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.terminal.TerminalSession;
import com.termux.shared.logger.Logger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class AiController {
    private static final String LOG_TAG = "AiController";
    private static final String API_KEY = "REPLACE_WITH_YOUR_OPENROUTER_API_KEY";
    private static final String MODEL = "openai/gpt-oss-120b:free";
    private static final String API_URL = "https://openrouter.ai/api/v1/chat/completions";

    private final TermuxTerminalViewClient mClient;
    private boolean mAiModeActive = false;
    private StringBuilder mInputBuffer = new StringBuilder();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<JSONObject> mHistory = new ArrayList<>();
    private boolean mIsWaitingForConfirmation = false;
    private String mPendingCommand = null;
    private Runnable mCountdownRunnable = null;

    public AiController(TermuxTerminalViewClient client) {
        this.mClient = client;
    }

    public boolean isAiModeActive() {
        return mAiModeActive;
    }

    public void setAiModeActive(boolean active) {
        this.mAiModeActive = active;
        if (active) {
            writeToTerminal("\r\n[AI Mode Activated. Type your request and press Enter]\r\n> ");
        } else {
            writeToTerminal("\r\n[AI Mode Deactivated]\r\n");
            cancelCountdown();
        }
    }

    public boolean handleCodePoint(int codePoint, TerminalSession session) {
        if (!mAiModeActive) {
            if (codePoint == 'a') {
                mInputBuffer.append('a');
            } else if (codePoint == 'i' && mInputBuffer.toString().equals("a")) {
                mInputBuffer.append('i');
            } else if (codePoint == 13 || codePoint == 10) { // Enter
                if (mInputBuffer.toString().equals("ai")) {
                    mInputBuffer.setLength(0);
                    setAiModeActive(true);
                    return true;
                }
                mInputBuffer.setLength(0);
            } else {
                mInputBuffer.setLength(0);
            }
            return false;
        }

        if (mIsWaitingForConfirmation) {
            if (codePoint == 'c' || codePoint == 'C') {
                cancelCountdown();
                writeToTerminal("\r\n[Cancelled]\r\n> ");
                return true;
            }
        }

        if (codePoint == 13 || codePoint == 10) { // Enter
            String request = mInputBuffer.toString();
            mInputBuffer.setLength(0);
            writeToTerminal("\r\n");
            processAiRequest(request, session);
            return true;
        } else if (codePoint == 127 || codePoint == 8) { // Backspace
            if (mInputBuffer.length() > 0) {
                mInputBuffer.setLength(mInputBuffer.length() - 1);
                writeToTerminal("\b \b");
            }
            return true;
        } else {
            mInputBuffer.append((char) codePoint);
            writeToTerminal(String.valueOf((char) codePoint));
            return true;
        }
    }

    private void writeToTerminal(String text) {
        TerminalSession session = mClient.getActivity().getCurrentSession();
        if (session != null) {
            session.getEmulator().append(text.getBytes(StandardCharsets.UTF_8), text.length());
            mClient.getActivity().getTerminalView().postInvalidate();
        }
    }

    private void cancelCountdown() {
        if (mCountdownRunnable != null) {
            mHandler.removeCallbacks(mCountdownRunnable);
            mCountdownRunnable = null;
        }
        mIsWaitingForConfirmation = false;
        mPendingCommand = null;
    }

    private void processAiRequest(String request, TerminalSession session) {
        if (request.trim().equalsIgnoreCase("exit") || request.trim().equalsIgnoreCase("quit")) {
            setAiModeActive(false);
            return;
        }

        writeToTerminal("[Thinking...]\r\n");
        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("model", MODEL);
                
                if (mHistory.isEmpty()) {
                    JSONObject systemMessage = new JSONObject();
                    systemMessage.put("role", "system");
                    systemMessage.put("content", "You are a Termux assistant. " +
                        "Translate the user's natural language into a single shell command or a script. " +
                        "Return ONLY the command(s) to be executed, no explanation, no markdown backticks.");
                    mHistory.add(systemMessage);
                }
                
                JSONObject userMessage = new JSONObject();
                userMessage.put("role", "user");
                userMessage.put("content", request);
                mHistory.add(userMessage);

                JSONArray messages = new JSONArray(mHistory);
                payload.put("messages", messages);

                URL url = new URL(API_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + API_KEY);
                conn.setRequestProperty("HTTP-Referer", "https://github.com/termux/termux-app");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                }

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    StringBuilder response = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            response.append(line);
                        }
                    }
                    
                    JSONObject jsonResponse = new JSONObject(response.toString());
                    String command = jsonResponse.getJSONArray("choices")
                            .getJSONObject(0).getJSONObject("message").getString("content").trim();
                    
                    JSONObject assistantMessage = new JSONObject();
                    assistantMessage.put("role", "assistant");
                    assistantMessage.put("content", command);
                    mHistory.add(assistantMessage);

                    mHandler.post(() -> startExecutionCountdown(command, session));
                } else {
                    writeToTerminal("[Error: " + responseCode + "]\r\n> ");
                }
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "AI request failed", e);
                mHandler.post(() -> writeToTerminal("[AI Error: " + e.getMessage() + ". Falling back to shell.]\r\n> "));
            }
        }).start();
    }

    private void startExecutionCountdown(String command, TerminalSession session) {
        mIsWaitingForConfirmation = true;
        mPendingCommand = command;
        final int[] secondsLeft = {7};
        writeToTerminal("Suggested command: " + command + "\r\n");
        
        mCountdownRunnable = new Runnable() {
            @Override
            public void run() {
                if (secondsLeft[0] > 0) {
                    writeToTerminal("\rExecuting in " + secondsLeft[0] + "s (Type 'c' to cancel)... ");
                    secondsLeft[0]--;
                    mHandler.postDelayed(this, 1000);
                } else {
                    writeToTerminal("\rExecuting...                                   \r\n");
                    executeCommand(command, session);
                    mCountdownRunnable = null;
                }
            }
        };
        mHandler.post(mCountdownRunnable);
    }

    private void executeCommand(String command, TerminalSession session) {
        mIsWaitingForConfirmation = false;
        mPendingCommand = null;
        byte[] bytes = (command + "\n").getBytes(StandardCharsets.UTF_8);
        session.write(bytes, 0, bytes.length);
        // We don't write "> " here because the shell prompt will appear after execution
    }
}
