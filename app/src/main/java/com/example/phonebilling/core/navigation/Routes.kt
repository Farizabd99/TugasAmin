package com.example.phonebilling.core.navigation

sealed class Route(val path: String) {
    data object OperatorLogin : Route("operator/login")
    data object OperatorDashboard : Route("operator/dashboard")
    data object DeviceList : Route("operator/devices")
    data object StartSession : Route("operator/start")
    data object ActiveSessionDetail : Route("operator/session/{sessionId}") {
        fun create(sessionId: String) = "operator/session/$sessionId"
    }
    data object BillingHistory : Route("operator/history")
    data object Settings : Route("operator/settings")
    data object ClientWaiting : Route("client/waiting")
    data object ClientActive : Route("client/active")
    data object ClientExpired : Route("client/expired")
}
