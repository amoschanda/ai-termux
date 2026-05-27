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
    // NOTE: Replace this with your actual OpenRouter API Key before building.
    private static final String API_KEY = "REPLACE_WITH_YOUR_OPENROUTER_API_KEY";
    private static final String MODEL = "openai/gpt-oss-120b:free";
    private static final String API_URL = "https://openrouter.ai/api/v1/chat/completions";

    // ANSI Color Codes
    private static final String COLOR_RESET = "\u001B[0m";
    private static final String COLOR_CYAN = "\u001B[36m";
    private static final String COLOR_GREEN = "\u001B[32m";
    private static final String COLOR_YELLOW = "\u001B[33m";
    private static final String COLOR_RED = "\u001B[31m";
    private static final String COLOR_BOLD = "\u001B[1m";

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
            writeToTerminal("\r\n" + COLOR_CYAN + COLOR_BOLD + "[AI Mode Activated]" + COLOR_RESET + "\r\n");
            writeToTerminal(COLOR_YELLOW + "Type your request in natural language (e.g., 'update my system' or 'install nodejs').\r\nType 'help' for info or 'exit' to return to shell." + COLOR_RESET + "\r\n> ");
        } else {
            writeToTerminal("\r\n" + COLOR_CYAN + "[AI Mode Deactivated]" + COLOR_RESET + "\r\n");
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
                writeToTerminal("\r\n" + COLOR_RED + "[Cancelled]" + COLOR_RESET + "\r\n> ");
                return true;
            }
        }

        if (codePoint == 13 || codePoint == 10) { // Enter
            String request = mInputBuffer.toString();
            mInputBuffer.setLength(0);
            writeToTerminal("\r\n");
            handleRequest(request, session);
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

    private void handleRequest(String request, TerminalSession session) {
        String trimmed = request.trim().toLowerCase();
        if (trimmed.equals("exit") || trimmed.equals("quit")) {
            setAiModeActive(false);
        } else if (trimmed.equals("help")) {
            showHelp();
        } else if (trimmed.equals("clear")) {
            mHistory.clear();
            writeToTerminal(COLOR_GREEN + "[Conversation history cleared]" + COLOR_RESET + "\r\n> ");
        } else if (!trimmed.isEmpty()) {
            processAiRequest(request, session);
        } else {
            writeToTerminal("> ");
        }
    }

    private void showHelp() {
        writeToTerminal(COLOR_BOLD + "AI Termux Help:" + COLOR_RESET + "\r\n");
        writeToTerminal("- Describe what you want to do in plain English.\r\n");
        writeToTerminal("- The AI suggests a command and waits 7 seconds before auto-running it.\r\n");
        writeToTerminal("- Press 'c' during the countdown to cancel.\r\n");
        writeToTerminal("- Commands: " + COLOR_YELLOW + "exit" + COLOR_RESET + " (back to shell), " + COLOR_YELLOW + "clear" + COLOR_RESET + " (reset AI memory), " + COLOR_YELLOW + "help" + COLOR_RESET + ".\r\n> ");
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
        writeToTerminal(COLOR_CYAN + "[Thinking...]" + COLOR_RESET + "\r\n");
        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("model", MODEL);
                
                if (mHistory.isEmpty()) {
                    JSONObject systemMessage = new JSONObject();
                    systemMessage.put("role", "system");
                    systemMessage.put("content", "You are an expert Termux shell assistant. " +
                        "Translate the user's natural language into the most accurate shell command(s). " +
                        "IMPORTANT: Return ONLY the raw command(s). No markdown, no backticks, no explanation.");
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
                    
                    // Basic cleanup in case AI includes backticks
                    command = command.replaceAll("```[a-z]*\n?", "").replaceAll("```", "");

                    JSONObject assistantMessage = new JSONObject();
                    assistantMessage.put("role", "assistant");
                    assistantMessage.put("content", command);
                    mHistory.add(assistantMessage);

                    final String finalCommand = command;
                    mHandler.post(() -> startExecutionCountdown(finalCommand, session));
                } else {
                    writeToTerminal(COLOR_RED + "[OpenRouter Error: " + responseCode + "]" + COLOR_RESET + "\r\n> ");
                }
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "AI request failed", e);
                mHandler.post(() -> writeToTerminal(COLOR_RED + "[AI Error: " + e.getMessage() + "]" + COLOR_RESET + "\r\n> "));
            }
        }).start();
    }

    private void startExecutionCountdown(String command, TerminalSession session) {
        mIsWaitingForConfirmation = true;
        mPendingCommand = command;
        final int[] secondsLeft = {7};
        writeToTerminal(COLOR_GREEN + COLOR_BOLD + "Suggested command: " + COLOR_RESET + COLOR_YELLOW + command + COLOR_RESET + "\r\n");
        
        mCountdownRunnable = new Runnable() {
            @Override
            public void run() {
                if (secondsLeft[0] > 0) {
                    writeToTerminal("\r" + COLOR_CYAN + "Executing in " + secondsLeft[0] + "s (Type 'c' to cancel)... " + COLOR_RESET);
                    secondsLeft[0]--;
                    mHandler.postDelayed(this, 1000);
                } else {
                    writeToTerminal("\r" + COLOR_GREEN + "Executing...                                   " + COLOR_RESET + "\r\n");
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
    }
}
