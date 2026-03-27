package com.example.agent.team;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 团队协议：shutdown 和 plan approval 握手。
 * <p>
 * 两种协议都使用 request_id 关联请求与响应：
 * <pre>
 * Shutdown:     Lead → shutdown_request(request_id) → Teammate
 *               Teammate → shutdown_response(request_id, approve) → Lead
 *
 * Plan Approval: Teammate → plan_approval(request_id, plan) → Lead
 *                Lead → plan_approval_response(request_id, approve) → Teammate
 * </pre>
 * <p>
 * 对应 Python 原版：s10_team_protocols.py
 */
public class TeamProtocol {

    /** Shutdown 请求跟踪器：request_id -> {target, status} */
    private final ConcurrentHashMap<String, Map<String, Object>> shutdownRequests = new ConcurrentHashMap<>();

    /** Plan 请求跟踪器：request_id -> {from, plan, status} */
    private final ConcurrentHashMap<String, Map<String, Object>> planRequests = new ConcurrentHashMap<>();

    private final MessageBus bus;

    public TeamProtocol(MessageBus bus) {
        this.bus = bus;
    }

    /**
     * Lead 发起 shutdown 请求。
     * <p>
     * 对应 Python: handle_shutdown_request(teammate)
     */
    public String requestShutdown(String teammate) {
        String reqId = UUID.randomUUID().toString().substring(0, 8);
        shutdownRequests.put(reqId, new ConcurrentHashMap<>(Map.of(
                "target", teammate, "status", "pending"
        )));
        bus.send("lead", teammate, "Please shut down gracefully.",
                "shutdown_request", Map.of("request_id", reqId));
        return "Shutdown request " + reqId + " sent to '" + teammate + "' (status: pending)";
    }

    /**
     * 检查 shutdown 请求状态。
     */
    public String checkShutdownStatus(String requestId) {
        var req = shutdownRequests.get(requestId);
        if (req == null) return "{\"error\": \"not found\"}";
        return req.toString();
    }

    /**
     * 更新 shutdown 请求状态（由 teammate 响应时调用）。
     */
    public void updateShutdownStatus(String requestId, boolean approved) {
        var req = shutdownRequests.get(requestId);
        if (req != null) {
            req.put("status", approved ? "approved" : "rejected");
        }
    }

    /**
     * Lead 审核 teammate 的 plan。
     * <p>
     * 对应 Python: handle_plan_review(request_id, approve, feedback)
     */
    public String reviewPlan(String requestId, boolean approve, String feedback) {
        var req = planRequests.get(requestId);
        if (req == null) return "Error: Unknown plan request_id '" + requestId + "'";
        req.put("status", approve ? "approved" : "rejected");
        bus.send("lead", (String) req.get("from"), feedback != null ? feedback : "",
                "plan_approval_response",
                Map.of("request_id", requestId, "approve", approve,
                        "feedback", feedback != null ? feedback : ""));
        return "Plan " + req.get("status") + " for '" + req.get("from") + "'";
    }

    /**
     * Teammate 提交 plan（由 teammate 内部调用）。
     */
    public String submitPlan(String sender, String planText) {
        String reqId = UUID.randomUUID().toString().substring(0, 8);
        planRequests.put(reqId, new ConcurrentHashMap<>(Map.of(
                "from", sender, "plan", planText, "status", "pending"
        )));
        bus.send(sender, "lead", planText, "plan_approval_response",
                Map.of("request_id", reqId, "plan", planText));
        return "Plan submitted (request_id=" + reqId + "). Waiting for lead approval.";
    }
}
