package com.insurance.renewal.backend

import kotlinx.serialization.Serializable

@Serializable
data class AgentDto(
    val id: String,
    val fullName: String,
    val email: String,
    val mobile: String,
    val passwordHash: String = "",
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val trialStartedAt: Long? = null,
    val trialExpiresAt: Long? = null
)

@Serializable
data class PolicyDto(
    val id: String,
    val agentId: String,
    val isDraft: Boolean = false,
    val title: String = "MR",
    val customerName: String = "",
    val customerEmail: String = "",
    val customerMobile: String = "",
    val customerNic: String = "",
    val addressLine1: String = "",
    val addressLine2: String = "",
    val addressLine3: String = "",
    val vehicleType: String = "MOTOR_BIKE",
    val vehicleNumber: String = "",
    val beneficiaryName: String = "",
    val beneficiaryNic: String = "",
    val beneficiaryRelationship: String = "SPOUSE",
    val nicFrontPath: String? = null,
    val nicRearPath: String? = null,
    val vrcPath: String? = null,
    val issueDate: String = "",
    val expiryDate: String = "",
    val status: String = "DRAFT",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val renewedAt: Long? = null,
    val deleted: Boolean = false
)

@Serializable
data class ReminderLogDto(
    val id: String,
    val policyId: String,
    val agentId: String,
    val channel: String,
    val message: String,
    val daysBeforeExpiry: Int? = null,
    val sentAt: Long = System.currentTimeMillis()
)

@Serializable
data class SyncPushRequest(
    val agent: AgentDto? = null,
    val policies: List<PolicyDto> = emptyList(),
    val reminderLogs: List<ReminderLogDto> = emptyList(),
    val deletedPolicyIds: List<String> = emptyList()
)

@Serializable
data class SyncPullResponse(
    val agent: AgentDto? = null,
    val policies: List<PolicyDto> = emptyList(),
    val reminderLogs: List<ReminderLogDto> = emptyList(),
    val deletedPolicyIds: List<String> = emptyList(),
    val license: LicenseDto? = null,
    val licensed: Boolean = false,
    val accessGranted: Boolean = false,
    val onTrial: Boolean = false,
    val trialStartedAt: Long? = null,
    val trialExpiresAt: Long? = null,
    val trialDaysLeft: Int? = null
)

@Serializable
data class AdminLoginRequest(val username: String, val password: String)

@Serializable
data class AgentSummary(
    val id: String,
    val fullName: String,
    val email: String,
    val mobile: String,
    val enabled: Boolean,
    val createdAt: Long,
    val policyCount: Int,
    val trialStartedAt: Long? = null,
    val trialExpiresAt: Long? = null,
    val onTrial: Boolean = false,
    val licensed: Boolean = false,
    val trialDaysLeft: Int? = null,
    /** LICENSED | TRIAL | EXPIRED */
    val accessStatus: String = "EXPIRED",
    val pendingResetRequest: Boolean = false
)

/** Admin PATCH body — omit a field (or null) to leave it unchanged. */
@Serializable
data class AdminUpdateAgentRequest(
    val fullName: String? = null,
    val email: String? = null,
    val mobile: String? = null,
    val enabled: Boolean? = null
)

@Serializable
data class AgentAuthRequest(
    val identifier: String,
    val password: String
)

@Serializable
data class PasswordResetRequestBody(
    val identifier: String
)

@Serializable
data class PasswordResetRequestDto(
    val id: String,
    val agentId: String?,
    val identifier: String,
    val status: String,
    val createdAt: Long,
    val resolvedAt: Long? = null,
    val agentName: String? = null,
    val agentEmail: String? = null,
    val agentMobile: String? = null
)

@Serializable
data class SetPasswordRequest(
    /** Plaintext password chosen by admin (hashed server-side). Empty → generate temp. */
    val password: String = "",
    val generateTemp: Boolean = false,
    /** Optional: mark this reset request completed. */
    val resetRequestId: String? = null,
    /** When true, email the temporary password to the agent's registered email (requires SMTP). */
    val emailToAgent: Boolean = false
)

@Serializable
data class SetPasswordResponse(
    val message: String,
    val ok: Boolean = true,
    /** Shown once so admin can communicate offline — never stored in plaintext. */
    val temporaryPassword: String? = null,
    val agentId: String,
    val emailSent: Boolean = false,
    val emailError: String? = null
)

@Serializable
data class SmtpSettingsDto(
    val host: String = "",
    val port: Int = 587,
    val username: String = "",
    /** Never returns the real password; true when a password is stored. */
    val passwordConfigured: Boolean = false,
    val fromEmail: String = "",
    val fromName: String = "RenewGuard",
    val useTls: Boolean = true,
    val configured: Boolean = false
)

@Serializable
data class SmtpSettingsUpdate(
    val host: String = "",
    val port: Int = 587,
    val username: String = "",
    /** Null or blank keeps the existing password. */
    val password: String? = null,
    val fromEmail: String = "",
    val fromName: String = "RenewGuard",
    val useTls: Boolean = true
)

@Serializable
data class TestEmailRequest(
    val to: String = ""
)

@Serializable
data class AdminStats(
    val totalAgents: Int,
    val activeAgents: Int,
    val totalPolicies: Int,
    val expiringSoon: Int,
    val expired: Int,
    val renewed: Int,
    val totalLicenses: Int = 0,
    val unusedLicenses: Int = 0,
    val activeLicenses: Int = 0,
    val expiredLicenses: Int = 0,
    val revokedLicenses: Int = 0,
    val onTrialAgents: Int = 0
)

@Serializable
data class LicenseDto(
    val id: String,
    val licenseKey: String,
    val agentId: String? = null,
    val agentName: String? = null,
    val agentEmail: String? = null,
    val issuedAt: Long,
    val activatedAt: Long? = null,
    val expiresAt: Long? = null,
    val status: String,
    val notes: String? = null
)

@Serializable
data class CreateLicensesRequest(
    val count: Int = 1,
    val notes: String? = null
)

@Serializable
data class CreateLicensesResponse(
    val created: List<LicenseDto>,
    val message: String
)

@Serializable
data class ActivateLicenseRequest(
    val agentId: String,
    val licenseKey: String
)

@Serializable
data class LicenseStatusResponse(
    val licensed: Boolean,
    val license: LicenseDto? = null,
    val message: String,
    val accessGranted: Boolean = false,
    val onTrial: Boolean = false,
    val trialStartedAt: Long? = null,
    val trialExpiresAt: Long? = null,
    val trialDaysLeft: Int? = null
)

@Serializable
data class AssignLicenseRequest(val agentId: String)

@Serializable
data class MessageResponse(val message: String, val ok: Boolean = true)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class AgentRegisterRequest(
    val fullName: String,
    val email: String,
    val mobile: String,
    val password: String
)

@Serializable
data class AgentChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String
)

@Serializable
data class AgentMeResponse(
    val agent: AgentDto,
    val licensed: Boolean = false,
    val accessGranted: Boolean = false,
    val onTrial: Boolean = false,
    val trialStartedAt: Long? = null,
    val trialExpiresAt: Long? = null,
    val trialDaysLeft: Int? = null,
    val license: LicenseDto? = null,
    val licenseMessage: String = ""
)

@Serializable
data class UploadResponse(
    val path: String,
    val url: String,
    val fileName: String
)

@Serializable
data class RetentionReportDto(
    val periodLabel: String,
    val upcomingRenewals: Int,
    val renewedCount: Int,
    val lapsedCount: Int,
    val activeCount: Int,
    val policies: List<PolicyDto> = emptyList()
)

@Serializable
data class ReportsResponse(
    val weekly: RetentionReportDto,
    val monthly: RetentionReportDto
)

object LicenseStatus {
    const val UNUSED = "UNUSED"
    const val ACTIVE = "ACTIVE"
    const val EXPIRED = "EXPIRED"
    const val REVOKED = "REVOKED"
}
