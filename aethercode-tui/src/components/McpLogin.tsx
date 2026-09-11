/**
 * T-428 (Phase 5 R4): McpLogin.
 *
 * 1:1 port of deepagents-code's `McpLogin.Screen` (Java port at
 * `McpLogin.java`). In-TUI OAuth login modal for MCP providers.
 *
 * design.md §5.2.3: "McpLogin.tsx, McpReconnect.tsx, McpViewer.tsx —
 * backed by the mcp_providers/ registry ported from deepagents-code.
 * Wire: mcp/listServers, mcp/login, mcp/refresh, mcp/listTools."
 *
 * The component is prop-driven. The host is the OAuth interaction
 * implementation: it receives `showAuthorizeUrl` /
 * `showDeviceCode` / `showSuccess` / `showError` / `showNotice`
 * callbacks and decides what to do with the data. The picker
 * itself just collects user input (cancel via Esc) and surfaces
 * the user's callback URL on submit.
 */

import React, { useState } from "react";
import { Box, Text, useInput } from "ink";
import Spinner from "ink-spinner";
import TextInput from "ink-text-input";

/** Outcome of the login flow. Mirrors the Java enum. */
export type LoginOutcome = "success" | "cancelled" | "failed";

/** OAuth interaction step. The host uses this to know which
 *  callback to fire next. */
export type LoginStep =
  | "starting"
  | "awaiting-authorize"
  | "device-code"
  | "success"
  | "error";

/** Public props. The host owns the OAuth dance and feeds the
 *  component view-state via these props. */
export interface McpLoginProps {
  /** The MCP server being signed into. */
  serverName: string;
  /** Current step. Drives the rendered state. */
  step: LoginStep;
  /** Optional authorize URL (browser-opened). */
  authorizeUrl?: string | null;
  /** Whether the host already opened `authorizeUrl` in the
   *  user's browser. The picker shows "(opened in browser)" or
   *  "(copy this URL)" accordingly. */
  authorizeOpened?: boolean;
  /** Optional device-code data (RFC 8628). */
  deviceCode?: { verificationUri: string; userCode: string; expiresIn: number } | null;
  /** Optional success / error / notice text. */
  message?: string | null;
  /** Called when the user submits the callback URL. */
  onSubmitCallback: (callbackUrl: string) => void;
  /** Called when the user cancels. */
  onCancel: () => void;
  /** Optional title override. */
  title?: string;
}

export const McpLogin: React.FC<McpLoginProps> = ({
  serverName,
  step,
  authorizeUrl,
  authorizeOpened = false,
  deviceCode,
  message,
  onSubmitCallback,
  onCancel,
  title,
}) => {
  // Show the callback URL input only when waiting for the
  // user to paste the redirect URI back.
  const awaitingCallback = step === "awaiting-authorize";
  const [callback, setCallback] = useState("");

  useInput((_input, key) => {
    if (key.escape) {
      onCancel();
      return;
    }
  });

  const submitCallback = (raw: string) => {
    const v = raw.trim();
    if (!v) return;
    onSubmitCallback(v);
  };

  return (
    <Box
      flexDirection="column"
      borderStyle="double"
      borderColor="cyan"
      paddingX={2}
      paddingY={1}
    >
      <Box>
        <Text color="cyan" bold>◆ {title ?? `MCP login: ${serverName}`}</Text>
      </Box>
      <Text> </Text>
      {step === "starting" ? (
        <Box>
          <Text color="cyan"><Spinner type="dots" /></Text>
          <Text>  Starting OAuth login for {serverName}…</Text>
        </Box>
      ) : null}
      {step === "awaiting-authorize" ? (
        <Box flexDirection="column">
          {authorizeUrl ? (
            <Box flexDirection="column">
              <Text>
                <Text dimColor>URL: </Text>
                <Text color="cyan">{authorizeUrl}</Text>
                <Text dimColor>  ({authorizeOpened ? "opened in browser" : "copy this URL"})</Text>
              </Text>
            </Box>
          ) : null}
          <Text> </Text>
          <Text dimColor>  Paste the callback URL after authorizing:</Text>
          <Box>
            <Text color="yellow">{">"}</Text>
            <Text> </Text>
            <TextInput
              value={callback}
              onChange={setCallback}
              onSubmit={submitCallback}
              placeholder="https://…  (callback URL)"
            />
          </Box>
        </Box>
      ) : null}
      {step === "device-code" && deviceCode ? (
        <Box flexDirection="column">
          <Text>
            <Text dimColor>Open: </Text>
            <Text color="cyan">{deviceCode.verificationUri}</Text>
          </Text>
          <Text>
            <Text dimColor>Enter code: </Text>
            <Text bold color="green">{deviceCode.userCode}</Text>
            <Text dimColor>  (expires in {deviceCode.expiresIn}s)</Text>
          </Text>
        </Box>
      ) : null}
      {step === "success" ? (
        <Box>
          <Text color="green">✓</Text>
          <Text>  {message ?? "Login successful"}</Text>
        </Box>
      ) : null}
      {step === "error" ? (
        <Box>
          <Text color="red">✗</Text>
          <Text>  {message ?? "Login failed"}</Text>
        </Box>
      ) : null}
      {message && step !== "success" && step !== "error" ? (
        <Text dimColor>  {message}</Text>
      ) : null}
      <Text> </Text>
      <Text dimColor>  {awaitingCallback ? "Enter: submit callback" : ""}  Esc: cancel</Text>
    </Box>
  );
};

/** Pure helper: format the OAuth state for tests + a possible
 *  call site that wants the same label (e.g. a status-bar
 *  pill). Mirrors `McpLogin.formatState` in deepagents-code. */
export function formatLoginState(step: LoginStep, serverName: string): string {
  switch (step) {
    case "starting":
      return `mcp login ${serverName}: starting…`;
    case "awaiting-authorize":
      return `mcp login ${serverName}: waiting for callback`;
    case "device-code":
      return `mcp login ${serverName}: device code`;
    case "success":
      return `mcp login ${serverName}: success`;
    case "error":
      return `mcp login ${serverName}: failed`;
  }
}
