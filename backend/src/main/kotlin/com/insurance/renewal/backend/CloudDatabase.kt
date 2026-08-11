package com.insurance.renewal.backend

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock

class CloudDatabase(private val dbFile: File) {
    private val url = "jdbc:sqlite:${dbFile.absolutePath}"
    private val rwLock = ReentrantReadWriteLock()

    /** Live SQLite file path (used by backup restore). */
    val databaseFile: File get() = dbFile

    init {
        dbFile.parentFile?.mkdirs()
        Class.forName("org.sqlite.JDBC")
        connection().use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS agents (
                      id TEXT PRIMARY KEY,
                      full_name TEXT NOT NULL,
                      email TEXT NOT NULL UNIQUE,
                      mobile TEXT NOT NULL,
                      password_hash TEXT NOT NULL,
                      enabled INTEGER NOT NULL DEFAULT 1,
                      created_at INTEGER NOT NULL,
                      trial_started_at INTEGER,
                      trial_expires_at INTEGER
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS policies (
                      id TEXT PRIMARY KEY,
                      agent_id TEXT NOT NULL,
                      is_draft INTEGER NOT NULL DEFAULT 0,
                      title TEXT,
                      customer_name TEXT,
                      customer_email TEXT,
                      customer_mobile TEXT,
                      customer_nic TEXT,
                      address_line1 TEXT,
                      address_line2 TEXT,
                      address_line3 TEXT,
                      vehicle_type TEXT,
                      vehicle_number TEXT,
                      beneficiary_name TEXT,
                      beneficiary_nic TEXT,
                      beneficiary_relationship TEXT,
                      nic_front_path TEXT,
                      nic_rear_path TEXT,
                      vrc_path TEXT,
                      issue_date TEXT,
                      expiry_date TEXT,
                      status TEXT,
                      created_at INTEGER,
                      updated_at INTEGER,
                      renewed_at INTEGER,
                      deleted INTEGER NOT NULL DEFAULT 0,
                      FOREIGN KEY(agent_id) REFERENCES agents(id)
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS reminder_logs (
                      id TEXT PRIMARY KEY,
                      policy_id TEXT NOT NULL,
                      agent_id TEXT NOT NULL,
                      channel TEXT,
                      message TEXT,
                      days_before_expiry INTEGER,
                      sent_at INTEGER
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS licenses (
                      id TEXT PRIMARY KEY,
                      license_key TEXT NOT NULL UNIQUE,
                      agent_id TEXT,
                      issued_at INTEGER NOT NULL,
                      activated_at INTEGER,
                      expires_at INTEGER,
                      status TEXT NOT NULL,
                      notes TEXT,
                      FOREIGN KEY(agent_id) REFERENCES agents(id)
                    )
                    """.trimIndent()
                )
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_policies_agent ON policies(agent_id)")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_policies_expiry ON policies(expiry_date)")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_licenses_key ON licenses(license_key)")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_licenses_agent ON licenses(agent_id)")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_licenses_status ON licenses(status)")
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS password_reset_requests (
                      id TEXT PRIMARY KEY,
                      agent_id TEXT,
                      identifier TEXT NOT NULL,
                      status TEXT NOT NULL DEFAULT 'PENDING',
                      created_at INTEGER NOT NULL,
                      resolved_at INTEGER,
                      FOREIGN KEY(agent_id) REFERENCES agents(id)
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_password_reset_status ON password_reset_requests(status)"
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS app_settings (
                      key TEXT PRIMARY KEY,
                      value TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
            ensureTrialColumns()
            refreshExpiredLicenses()
        }
    }

    object SettingKeys {
        const val SMTP_HOST = "smtp.host"
        const val SMTP_PORT = "smtp.port"
        const val SMTP_USERNAME = "smtp.username"
        const val SMTP_PASSWORD = "smtp.password"
        const val SMTP_FROM_EMAIL = "smtp.from_email"
        const val SMTP_FROM_NAME = "smtp.from_name"
        const val SMTP_USE_TLS = "smtp.use_tls"
    }

    companion object {
        private val YEAR_MS = TimeUnit.DAYS.toMillis(365)
        private val TRIAL_MS = TimeUnit.DAYS.toMillis(90)
        private val KEY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private val secureRandom = SecureRandom()

        fun generateLicenseKey(): String {
            fun segment() = buildString {
                repeat(4) { append(KEY_ALPHABET[secureRandom.nextInt(KEY_ALPHABET.length)]) }
            }
            return "RG-${segment()}-${segment()}-${segment()}"
        }

        fun trialDaysLeft(expiresAt: Long?, now: Long = System.currentTimeMillis()): Int {
            if (expiresAt == null || expiresAt <= now) return 0
            val dayMs = TimeUnit.DAYS.toMillis(1)
            return (((expiresAt - now) + dayMs - 1) / dayMs).toInt().coerceAtLeast(1)
        }
    }

    private fun ensureTrialColumns() {
        connection().use { conn ->
            val cols = conn.createStatement().executeQuery("PRAGMA table_info(agents)").use { rs ->
                buildSet {
                    while (rs.next()) add(rs.getString("name"))
                }
            }
            if ("trial_started_at" !in cols) {
                conn.createStatement().executeUpdate(
                    "ALTER TABLE agents ADD COLUMN trial_started_at INTEGER"
                )
            }
            if ("trial_expires_at" !in cols) {
                conn.createStatement().executeUpdate(
                    "ALTER TABLE agents ADD COLUMN trial_expires_at INTEGER"
                )
            }
            // Backfill from registration for existing agents
            conn.createStatement().executeUpdate(
                """
                UPDATE agents
                SET trial_started_at = created_at
                WHERE trial_started_at IS NULL
                """.trimIndent()
            )
            conn.prepareStatement(
                """
                UPDATE agents
                SET trial_expires_at = trial_started_at + ?
                WHERE trial_expires_at IS NULL AND trial_started_at IS NOT NULL
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, TRIAL_MS)
                ps.executeUpdate()
            }
        }
    }

    /**
     * Opens a connection while holding a shared read lock so restore can take an exclusive
     * write lock and guarantee no open JDBC handles when replacing the DB file.
     */
    private fun connection(): Connection {
        val lock = rwLock.readLock()
        lock.lock()
        try {
            val conn = DriverManager.getConnection(url).also {
                it.createStatement().execute("PRAGMA foreign_keys = ON")
            }
            return object : Connection by conn {
                override fun close() {
                    try {
                        conn.close()
                    } finally {
                        lock.unlock()
                    }
                }
            }
        } catch (e: Exception) {
            lock.unlock()
            throw e
        }
    }

    /** Blocks other DB access while [block] runs (used during restore file swap). */
    fun <T> withExclusiveAccess(block: () -> T): T {
        val lock = rwLock.writeLock()
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Replace the live database file. Must be called inside [withExclusiveAccess].
     * Caller is responsible for creating a pre-restore safety copy first.
     */
    fun replaceDatabaseFile(sourceDb: File) {
        require(sourceDb.isFile) { "Replacement database file is missing" }
        dbFile.parentFile?.mkdirs()
        // Drop sidecar files from WAL mode if present so the new DB opens cleanly.
        File("${dbFile.path}-wal").delete()
        File("${dbFile.path}-shm").delete()
        Files.copy(sourceDb.toPath(), dbFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /** Re-apply lightweight migrations / license refresh after a restore. */
    fun afterRestore() {
        ensureTrialColumns()
        refreshExpiredLicenses()
    }

    fun upsertAgent(agent: AgentDto) {
        val started = agent.trialStartedAt ?: agent.createdAt
        val expires = agent.trialExpiresAt ?: (started + TRIAL_MS)
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO agents(id, full_name, email, mobile, password_hash, enabled, created_at, trial_started_at, trial_expires_at)
                VALUES(?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET
                  full_name=excluded.full_name,
                  email=excluded.email,
                  mobile=excluded.mobile,
                  password_hash=CASE WHEN excluded.password_hash='' THEN agents.password_hash ELSE excluded.password_hash END,
                  enabled=excluded.enabled,
                  trial_started_at=COALESCE(agents.trial_started_at, excluded.trial_started_at),
                  trial_expires_at=COALESCE(agents.trial_expires_at, excluded.trial_expires_at)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, agent.id)
                ps.setString(2, agent.fullName)
                ps.setString(3, agent.email.lowercase())
                ps.setString(4, agent.mobile)
                ps.setString(5, agent.passwordHash)
                ps.setInt(6, if (agent.enabled) 1 else 0)
                ps.setLong(7, agent.createdAt)
                ps.setLong(8, started)
                ps.setLong(9, expires)
                ps.executeUpdate()
            }
        }
    }

    fun getAgent(id: String): AgentDto? = connection().use { conn ->
        conn.prepareStatement("SELECT * FROM agents WHERE id=?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null else rs.toAgent()
            }
        }
    }

    fun listAgents(query: String? = null, accessFilter: String? = null): List<AgentSummary> = connection().use { conn ->
        refreshExpiredLicenses()
        val q = query?.trim().orEmpty()
        val filter = accessFilter?.trim()?.uppercase().orEmpty()
        val sql = if (q.isBlank()) {
            """
            SELECT a.*,
              (SELECT COUNT(*) FROM policies p WHERE p.agent_id=a.id AND p.deleted=0) AS policy_count,
              (SELECT COUNT(*) FROM licenses l WHERE l.agent_id=a.id AND l.status='${LicenseStatus.ACTIVE}'
                AND l.expires_at IS NOT NULL AND l.expires_at > ?) AS has_license,
              (SELECT COUNT(*) FROM password_reset_requests r WHERE r.agent_id=a.id AND r.status='PENDING') AS pending_resets
            FROM agents a
            ORDER BY a.created_at DESC
            """.trimIndent()
        } else {
            """
            SELECT a.*,
              (SELECT COUNT(*) FROM policies p WHERE p.agent_id=a.id AND p.deleted=0) AS policy_count,
              (SELECT COUNT(*) FROM licenses l WHERE l.agent_id=a.id AND l.status='${LicenseStatus.ACTIVE}'
                AND l.expires_at IS NOT NULL AND l.expires_at > ?) AS has_license,
              (SELECT COUNT(*) FROM password_reset_requests r WHERE r.agent_id=a.id AND r.status='PENDING') AS pending_resets
            FROM agents a
            WHERE lower(a.full_name) LIKE ? OR lower(a.email) LIKE ?
              OR lower(a.mobile) LIKE ? OR lower(a.id) LIKE ?
            ORDER BY a.created_at DESC
            """.trimIndent()
        }
        val now = System.currentTimeMillis()
        val all = if (q.isBlank()) {
            conn.prepareStatement(sql).use { ps ->
                ps.setLong(1, now)
                ps.executeQuery().use { rs -> rs.toAgentSummaries(now) }
            }
        } else {
            val like = "%${q.lowercase()}%"
            conn.prepareStatement(sql).use { ps ->
                ps.setLong(1, now)
                repeat(4) { ps.setString(it + 2, like) }
                ps.executeQuery().use { rs -> rs.toAgentSummaries(now) }
            }
        }
        when (filter) {
            "TRIAL" -> all.filter { it.accessStatus == "TRIAL" }
            "LICENSED" -> all.filter { it.accessStatus == "LICENSED" }
            "EXPIRED" -> all.filter { it.accessStatus == "EXPIRED" }
            "ENABLED", "ACTIVE" -> all.filter { it.enabled }
            "DISABLED" -> all.filter { !it.enabled }
            else -> all
        }
    }

    private fun java.sql.ResultSet.toAgentSummaries(now: Long): List<AgentSummary> = buildList {
        while (next()) {
            val trialStarted = getObject("trial_started_at")?.let { (it as Number).toLong() }
            val trialExpires = getObject("trial_expires_at")?.let { (it as Number).toLong() }
            val licensed = getInt("has_license") > 0
            val onTrial = !licensed && trialExpires != null && trialExpires > now
            val accessStatus = when {
                licensed -> "LICENSED"
                onTrial -> "TRIAL"
                else -> "EXPIRED"
            }
            add(
                AgentSummary(
                    id = getString("id"),
                    fullName = getString("full_name"),
                    email = getString("email"),
                    mobile = getString("mobile"),
                    enabled = getInt("enabled") == 1,
                    createdAt = getLong("created_at"),
                    policyCount = getInt("policy_count"),
                    trialStartedAt = trialStarted,
                    trialExpiresAt = trialExpires,
                    onTrial = onTrial,
                    licensed = licensed,
                    trialDaysLeft = if (onTrial) trialDaysLeft(trialExpires, now) else null,
                    accessStatus = accessStatus,
                    pendingResetRequest = getInt("pending_resets") > 0
                )
            )
        }
    }

    fun setAgentEnabled(id: String, enabled: Boolean): Boolean = connection().use { conn ->
        conn.prepareStatement("UPDATE agents SET enabled=? WHERE id=?").use { ps ->
            ps.setInt(1, if (enabled) 1 else 0)
            ps.setString(2, id)
            ps.executeUpdate() > 0
        }
    }

    /**
     * Updates agent profile fields from admin. Only non-null arguments are applied.
     * @throws NoSuchElementException if agent missing
     * @throws IllegalArgumentException on validation / uniqueness failures
     */
    fun updateAgentDetails(
        id: String,
        fullName: String? = null,
        email: String? = null,
        mobile: String? = null,
        enabled: Boolean? = null
    ): AgentDto {
        val existing = getAgent(id) ?: throw NoSuchElementException("Agent not found")
        val newName = fullName?.trim()?.takeIf { it.isNotEmpty() } ?: existing.fullName
        val newEmail = email?.trim()?.takeIf { it.isNotEmpty() } ?: existing.email
        val newMobile = mobile?.trim()?.takeIf { it.isNotEmpty() }?.let { normalizeMobile(it) } ?: existing.mobile
        val newEnabled = enabled ?: existing.enabled

        require(newName.isNotBlank()) { "Full name is required" }
        require(newEmail.contains("@") && newEmail.length >= 5) { "Valid email is required" }
        require(newMobile.filter { it.isDigit() }.length >= 9) { "Valid mobile number is required" }

        if (!newEmail.equals(existing.email, ignoreCase = true) && emailTaken(newEmail, id)) {
            throw IllegalArgumentException("Email already in use by another agent")
        }
        if (newMobile != existing.mobile && mobileTaken(newMobile, id)) {
            throw IllegalArgumentException("Mobile already in use by another agent")
        }

        connection().use { conn ->
            conn.prepareStatement(
                """
                UPDATE agents
                SET full_name=?, email=?, mobile=?, enabled=?
                WHERE id=?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, newName)
                ps.setString(2, newEmail)
                ps.setString(3, newMobile)
                ps.setInt(4, if (newEnabled) 1 else 0)
                ps.setString(5, id)
                if (ps.executeUpdate() == 0) throw NoSuchElementException("Agent not found")
            }
        }
        return getAgent(id) ?: throw NoSuchElementException("Agent not found")
    }

    fun findAgentByIdentifier(identifier: String): AgentDto? {
        val id = identifier.trim()
        if (id.isBlank()) return null
        connection().use { conn ->
            if (id.contains("@")) {
                conn.prepareStatement("SELECT * FROM agents WHERE lower(email)=lower(?)").use { ps ->
                    ps.setString(1, id)
                    ps.executeQuery().use { rs -> if (rs.next()) return rs.toAgent() }
                }
            }
            val digits = id.filter { it.isDigit() }
            val candidates = buildList {
                add(id)
                if (digits.isNotEmpty()) {
                    add(digits)
                    if (digits.length == 9) add("0$digits")
                    if (digits.length == 10 && digits.startsWith("0")) {
                        add("+94${digits.drop(1)}")
                        add(digits.drop(1))
                    }
                    if (digits.length >= 9) add("+94${digits.takeLast(9)}")
                }
            }.distinct()
            for (mobile in candidates) {
                conn.prepareStatement("SELECT * FROM agents WHERE mobile=?").use { ps ->
                    ps.setString(1, mobile)
                    ps.executeQuery().use { rs -> if (rs.next()) return rs.toAgent() }
                }
            }
        }
        return null
    }

    fun authenticateAgent(identifier: String, password: String): AgentDto? {
        val agent = findAgentByIdentifier(identifier) ?: return null
        if (!agent.enabled) return null
        if (!PasswordHasher.matches(password, agent.passwordHash)) return null
        return agent
    }

    fun emailTaken(email: String, excludeAgentId: String? = null): Boolean = connection().use { conn ->
        val sql = if (excludeAgentId == null) {
            "SELECT 1 FROM agents WHERE lower(email)=lower(?)"
        } else {
            "SELECT 1 FROM agents WHERE lower(email)=lower(?) AND id!=?"
        }
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, email.trim())
            if (excludeAgentId != null) ps.setString(2, excludeAgentId)
            ps.executeQuery().use { it.next() }
        }
    }

    fun mobileTaken(mobile: String, excludeAgentId: String? = null): Boolean {
        val candidates = mobileCandidates(mobile)
        if (candidates.isEmpty()) return false
        return connection().use { conn ->
            for (m in candidates) {
                val sql = if (excludeAgentId == null) {
                    "SELECT 1 FROM agents WHERE mobile=?"
                } else {
                    "SELECT 1 FROM agents WHERE mobile=? AND id!=?"
                }
                conn.prepareStatement(sql).use { ps ->
                    ps.setString(1, m)
                    if (excludeAgentId != null) ps.setString(2, excludeAgentId)
                    ps.executeQuery().use { if (it.next()) return true }
                }
            }
            false
        }
    }

    private fun mobileCandidates(raw: String): List<String> {
        val id = raw.trim()
        if (id.isBlank()) return emptyList()
        val digits = id.filter { it.isDigit() }
        return buildList {
            add(id)
            if (digits.isNotEmpty()) {
                add(digits)
                if (digits.length == 9) add("0$digits")
                if (digits.length == 10 && digits.startsWith("0")) {
                    add("+94${digits.drop(1)}")
                    add(digits.drop(1))
                }
                if (digits.length >= 9) add("+94${digits.takeLast(9)}")
            }
        }.distinct()
    }

    fun registerAgent(fullName: String, email: String, mobile: String, password: String): AgentDto {
        val name = fullName.trim()
        val mail = email.trim().lowercase()
        val phone = normalizeMobile(mobile)
        require(name.length >= 2) { "Enter your full name" }
        require(isEmail(mail)) { "Enter a valid email" }
        require(isMobile(mobile)) { "Enter a valid mobile number" }
        require(password.length >= 6) { "Password must be at least 6 characters" }
        require(!emailTaken(mail)) { "Email already registered" }
        require(!mobileTaken(phone) && !mobileTaken(mobile)) { "Mobile already registered" }

        val now = System.currentTimeMillis()
        val agent = AgentDto(
            id = UUID.randomUUID().toString(),
            fullName = name,
            email = mail,
            mobile = phone,
            passwordHash = PasswordHasher.hash(password),
            enabled = true,
            createdAt = now,
            trialStartedAt = now,
            trialExpiresAt = now + TRIAL_MS
        )
        upsertAgent(agent)
        return agent
    }

    fun changeAgentPassword(agentId: String, currentPassword: String, newPassword: String): AgentDto {
        val agent = getAgent(agentId) ?: throw IllegalArgumentException("Not logged in")
        require(newPassword.length >= 6) { "New password must be at least 6 characters" }
        require(PasswordHasher.matches(currentPassword, agent.passwordHash)) {
            "Current password is incorrect"
        }
        setAgentPassword(agentId, newPassword)
        return getAgent(agentId)!!
    }

    fun reminderLogsForPolicy(policyId: String, agentId: String): List<ReminderLogDto> =
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT * FROM reminder_logs
                WHERE policy_id=? AND agent_id=?
                ORDER BY sent_at DESC LIMIT 200
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, policyId)
                ps.setString(2, agentId)
                ps.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.toReminder()) }
                }
            }
        }

    fun softDeletePolicyForAgent(id: String, agentId: String): Boolean = connection().use { conn ->
        conn.prepareStatement(
            "UPDATE policies SET deleted=1, updated_at=? WHERE id=? AND agent_id=?"
        ).use { ps ->
            ps.setLong(1, System.currentTimeMillis())
            ps.setString(2, id)
            ps.setString(3, agentId)
            ps.executeUpdate() > 0
        }
    }

    fun markPolicyRenewed(policyId: String, agentId: String): PolicyDto? {
        val existing = getPolicy(policyId) ?: return null
        if (existing.agentId != agentId || existing.deleted) return null
        val today = LocalDate.now()
        val issue = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val expiry = today.plusYears(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val now = System.currentTimeMillis()
        val updated = existing.copy(
            isDraft = false,
            status = "ACTIVE",
            issueDate = issue,
            expiryDate = expiry,
            renewedAt = now,
            updatedAt = now,
            deleted = false
        )
        upsertPolicy(updated)
        return updated
    }

    fun buildReports(agentId: String): ReportsResponse {
        val today = LocalDate.now()
        val all = policiesForAgent(agentId).map { refreshPolicyStatus(it, today) }
        return ReportsResponse(
            weekly = buildReport(all, "Next 7 days", today, today.plusDays(7)),
            monthly = buildReport(all, "Next 30 days", today, today.plusDays(30))
        )
    }

    private fun refreshPolicyStatus(policy: PolicyDto, today: LocalDate): PolicyDto {
        if (policy.isDraft || policy.status == "RENEWED") return policy
        val expiry = runCatching { LocalDate.parse(policy.expiryDate) }.getOrNull() ?: return policy
        val days = ChronoUnit.DAYS.between(today, expiry)
        val status = when {
            days < 0 -> "EXPIRED"
            days <= 30 -> "EXPIRING_SOON"
            else -> "ACTIVE"
        }
        return if (status == policy.status) policy else policy.copy(status = status)
    }

    private fun buildReport(
        all: List<PolicyDto>,
        label: String,
        from: LocalDate,
        to: LocalDate
    ): RetentionReportDto {
        val tracking = all.filter { !it.isDraft }
        val upcoming = tracking.filter {
            val e = runCatching { LocalDate.parse(it.expiryDate) }.getOrNull() ?: return@filter false
            !e.isBefore(from) && !e.isAfter(to) && it.status != "RENEWED"
        }
        val monthStart = from.withDayOfMonth(1)
        val renewed = tracking.count {
            val at = it.renewedAt ?: return@count false
            val d = java.time.Instant.ofEpochMilli(at)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
            !d.isBefore(monthStart)
        }
        val lapsed = tracking.count { it.status == "EXPIRED" }
        val active = tracking.count {
            it.status == "ACTIVE" || it.status == "EXPIRING_SOON"
        }
        return RetentionReportDto(
            periodLabel = label,
            upcomingRenewals = upcoming.size,
            renewedCount = renewed,
            lapsedCount = lapsed,
            activeCount = active,
            policies = upcoming.sortedBy { it.expiryDate }
        )
    }

    private fun isEmail(value: String): Boolean =
        value.matches(Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"))

    private fun isMobile(value: String): Boolean {
        val digits = value.filter { it.isDigit() }
        return when {
            digits.length == 10 && digits.startsWith("0") -> true
            digits.length == 9 -> true
            digits.length == 11 && digits.startsWith("94") -> true
            digits.length == 12 && digits.startsWith("94") -> true
            else -> false
        }
    }

    private fun normalizeMobile(raw: String): String {
        val digits = raw.filter { it.isDigit() || it == '+' }
        return when {
            digits.startsWith("+") -> digits
            digits.length == 10 && digits.startsWith("0") -> "+94${digits.drop(1)}"
            digits.length == 9 -> "+94$digits"
            digits.length == 11 && digits.startsWith("94") -> "+$digits"
            else -> digits
        }
    }

    fun setAgentPassword(agentId: String, plaintextPassword: String): Boolean {
        val hash = PasswordHasher.hash(plaintextPassword)
        return connection().use { conn ->
            conn.prepareStatement("UPDATE agents SET password_hash=? WHERE id=?").use { ps ->
                ps.setString(1, hash)
                ps.setString(2, agentId)
                ps.executeUpdate() > 0
            }
        }
    }

    fun createPasswordResetRequest(identifier: String): PasswordResetRequestDto {
        val trimmed = identifier.trim()
        require(trimmed.isNotBlank()) { "Enter email or mobile" }
        val agent = findAgentByIdentifier(trimmed)
            ?: throw IllegalArgumentException("No active account found for that email or mobile")
        require(agent.enabled) {
            "This account is disabled. Contact your administrator."
        }
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        connection().use { conn ->
            // Avoid flooding: if a pending request already exists for this agent, return it.
            conn.prepareStatement(
                """
                SELECT * FROM password_reset_requests
                WHERE agent_id=? AND status='PENDING'
                ORDER BY created_at DESC LIMIT 1
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, agent.id)
                ps.executeQuery().use { rs ->
                    if (rs.next()) return rs.toPasswordReset(agent)
                }
            }
            conn.prepareStatement(
                """
                INSERT INTO password_reset_requests(id, agent_id, identifier, status, created_at, resolved_at)
                VALUES(?,?,?,'PENDING',?,NULL)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, agent.id)
                ps.setString(3, trimmed)
                ps.setLong(4, now)
                ps.executeUpdate()
            }
        }
        return PasswordResetRequestDto(
            id = id,
            agentId = agent.id,
            identifier = trimmed,
            status = "PENDING",
            createdAt = now,
            agentName = agent.fullName,
            agentEmail = agent.email,
            agentMobile = agent.mobile
        )
    }

    fun listPasswordResetRequests(status: String? = "PENDING"): List<PasswordResetRequestDto> =
        connection().use { conn ->
            val filter = status?.trim()?.uppercase().orEmpty()
            val sql = if (filter.isBlank() || filter == "ALL") {
                """
                SELECT r.*, a.full_name, a.email AS agent_email, a.mobile AS agent_mobile
                FROM password_reset_requests r
                LEFT JOIN agents a ON a.id = r.agent_id
                ORDER BY
                  CASE WHEN r.status='PENDING' THEN 0 ELSE 1 END,
                  r.created_at DESC
                """.trimIndent()
            } else {
                """
                SELECT r.*, a.full_name, a.email AS agent_email, a.mobile AS agent_mobile
                FROM password_reset_requests r
                LEFT JOIN agents a ON a.id = r.agent_id
                WHERE r.status=?
                ORDER BY r.created_at DESC
                """.trimIndent()
            }
            if (filter.isBlank() || filter == "ALL") {
                conn.prepareStatement(sql).use { ps ->
                    ps.executeQuery().use { rs ->
                        buildList { while (rs.next()) add(rs.toPasswordResetJoined()) }
                    }
                }
            } else {
                conn.prepareStatement(sql).use { ps ->
                    ps.setString(1, filter)
                    ps.executeQuery().use { rs ->
                        buildList { while (rs.next()) add(rs.toPasswordResetJoined()) }
                    }
                }
            }
        }

    fun completePasswordResetRequests(agentId: String, resetRequestId: String? = null) {
        val now = System.currentTimeMillis()
        connection().use { conn ->
            if (!resetRequestId.isNullOrBlank()) {
                conn.prepareStatement(
                    "UPDATE password_reset_requests SET status='COMPLETED', resolved_at=? WHERE id=?"
                ).use { ps ->
                    ps.setLong(1, now)
                    ps.setString(2, resetRequestId)
                    ps.executeUpdate()
                }
            }
            conn.prepareStatement(
                """
                UPDATE password_reset_requests
                SET status='COMPLETED', resolved_at=?
                WHERE agent_id=? AND status='PENDING'
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, now)
                ps.setString(2, agentId)
                ps.executeUpdate()
            }
        }
    }

    fun deletePasswordResetRequest(id: String): Boolean =
        connection().use { conn ->
            conn.prepareStatement("DELETE FROM password_reset_requests WHERE id=?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate() > 0
            }
        }

    private fun java.sql.ResultSet.toPasswordReset(agent: AgentDto?) = PasswordResetRequestDto(
        id = getString("id"),
        agentId = getString("agent_id"),
        identifier = getString("identifier"),
        status = getString("status"),
        createdAt = getLong("created_at"),
        resolvedAt = getObject("resolved_at")?.let { (it as Number).toLong() },
        agentName = agent?.fullName,
        agentEmail = agent?.email,
        agentMobile = agent?.mobile
    )

    private fun java.sql.ResultSet.toPasswordResetJoined() = PasswordResetRequestDto(
        id = getString("id"),
        agentId = getString("agent_id"),
        identifier = getString("identifier"),
        status = getString("status"),
        createdAt = getLong("created_at"),
        resolvedAt = getObject("resolved_at")?.let { (it as Number).toLong() },
        agentName = getString("full_name"),
        agentEmail = getString("agent_email"),
        agentMobile = getString("agent_mobile")
    )

    fun deleteAgent(id: String): Boolean = connection().use { conn ->
        conn.autoCommit = false
        try {
            conn.prepareStatement(
                "UPDATE licenses SET status=?, agent_id=NULL WHERE agent_id=?"
            ).use {
                it.setString(1, LicenseStatus.REVOKED)
                it.setString(2, id)
                it.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM reminder_logs WHERE agent_id=?").use {
                it.setString(1, id); it.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM password_reset_requests WHERE agent_id=?").use {
                it.setString(1, id); it.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM policies WHERE agent_id=?").use {
                it.setString(1, id); it.executeUpdate()
            }
            val deleted = conn.prepareStatement("DELETE FROM agents WHERE id=?").use {
                it.setString(1, id); it.executeUpdate()
            }
            conn.commit()
            deleted > 0
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    fun refreshExpiredLicenses() {
        val now = System.currentTimeMillis()
        connection().use { conn ->
            conn.prepareStatement(
                """
                UPDATE licenses SET status=?
                WHERE status=? AND expires_at IS NOT NULL AND expires_at < ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, LicenseStatus.EXPIRED)
                ps.setString(2, LicenseStatus.ACTIVE)
                ps.setLong(3, now)
                ps.executeUpdate()
            }
        }
    }

    fun createLicenses(count: Int, notes: String?): List<LicenseDto> {
        require(count in 1..100) { "Count must be between 1 and 100" }
        val now = System.currentTimeMillis()
        val created = mutableListOf<LicenseDto>()
        connection().use { conn ->
            conn.autoCommit = false
            try {
                repeat(count) {
                    var key: String
                    do {
                        key = generateLicenseKey()
                    } while (licenseKeyExists(conn, key))
                    val id = UUID.randomUUID().toString()
                    conn.prepareStatement(
                        """
                        INSERT INTO licenses(id, license_key, agent_id, issued_at, activated_at, expires_at, status, notes)
                        VALUES(?,?,NULL,?,NULL,NULL,?,?)
                        """.trimIndent()
                    ).use { ps ->
                        ps.setString(1, id)
                        ps.setString(2, key)
                        ps.setLong(3, now)
                        ps.setString(4, LicenseStatus.UNUSED)
                        ps.setString(5, notes?.trim()?.ifBlank { null })
                        ps.executeUpdate()
                    }
                    created += LicenseDto(
                        id = id,
                        licenseKey = key,
                        issuedAt = now,
                        status = LicenseStatus.UNUSED,
                        notes = notes?.trim()?.ifBlank { null }
                    )
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
        return created
    }

    private fun licenseKeyExists(conn: Connection, key: String): Boolean {
        conn.prepareStatement("SELECT 1 FROM licenses WHERE license_key=?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { return it.next() }
        }
    }

    fun listLicenses(query: String? = null, status: String? = null): List<LicenseDto> {
        refreshExpiredLicenses()
        return connection().use { conn ->
            val q = query?.trim().orEmpty()
            val st = status?.trim()?.uppercase().orEmpty()
            val clauses = mutableListOf<String>()
            val args = mutableListOf<Any>()
            if (st.isNotBlank() && st != "ALL") {
                clauses += "l.status=?"
                args += st
            }
            if (q.isNotBlank()) {
                clauses += """(
                  lower(l.license_key) LIKE ? OR lower(coalesce(l.agent_id,'')) LIKE ?
                  OR lower(coalesce(a.full_name,'')) LIKE ? OR lower(coalesce(a.email,'')) LIKE ?
                  OR lower(coalesce(l.notes,'')) LIKE ?
                )"""
                val like = "%${q.lowercase()}%"
                repeat(5) { args += like }
            }
            val where = if (clauses.isEmpty()) "" else "WHERE " + clauses.joinToString(" AND ")
            val sql = """
                SELECT l.*, a.full_name AS agent_name, a.email AS agent_email
                FROM licenses l
                LEFT JOIN agents a ON a.id = l.agent_id
                $where
                ORDER BY l.issued_at DESC
                LIMIT 500
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                args.forEachIndexed { i, v ->
                    when (v) {
                        is String -> ps.setString(i + 1, v)
                        is Long -> ps.setLong(i + 1, v)
                        else -> ps.setObject(i + 1, v)
                    }
                }
                ps.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.toLicense()) }
                }
            }
        }
    }

    fun getLicense(id: String): LicenseDto? {
        refreshExpiredLicenses()
        return connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT l.*, a.full_name AS agent_name, a.email AS agent_email
                FROM licenses l
                LEFT JOIN agents a ON a.id = l.agent_id
                WHERE l.id=?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toLicense() else null }
            }
        }
    }

    fun getLicenseByKey(key: String): LicenseDto? {
        refreshExpiredLicenses()
        val normalized = normalizeLicenseKey(key)
        return connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT l.*, a.full_name AS agent_name, a.email AS agent_email
                FROM licenses l
                LEFT JOIN agents a ON a.id = l.agent_id
                WHERE l.license_key=?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, normalized)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toLicense() else null }
            }
        }
    }

    fun getActiveLicenseForAgent(agentId: String): LicenseDto? {
        refreshExpiredLicenses()
        return connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT l.*, a.full_name AS agent_name, a.email AS agent_email
                FROM licenses l
                LEFT JOIN agents a ON a.id = l.agent_id
                WHERE l.agent_id=? AND l.status=?
                ORDER BY l.expires_at DESC
                LIMIT 1
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, agentId)
                ps.setString(2, LicenseStatus.ACTIVE)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toLicense() else null }
            }
        }
    }

    fun getLicenseStatusForAgent(agentId: String): LicenseStatusResponse {
        refreshExpiredLicenses()
        val agent = getAgent(agentId)
        val trialStarted = agent?.trialStartedAt
        val trialExpires = agent?.trialExpiresAt
        val now = System.currentTimeMillis()
        val onTrial = trialExpires != null && trialExpires > now
        val daysLeft = if (onTrial) trialDaysLeft(trialExpires, now) else 0

        val active = getActiveLicenseForAgent(agentId)
        if (active != null) {
            return LicenseStatusResponse(
                licensed = true,
                license = active,
                message = "License active until ${formatExpiry(active.expiresAt)}",
                accessGranted = true,
                onTrial = false,
                trialStartedAt = trialStarted,
                trialExpiresAt = trialExpires,
                trialDaysLeft = null
            )
        }
        if (onTrial) {
            return LicenseStatusResponse(
                licensed = false,
                license = null,
                message = "Free trial active — $daysLeft days left",
                accessGranted = true,
                onTrial = true,
                trialStartedAt = trialStarted,
                trialExpiresAt = trialExpires,
                trialDaysLeft = daysLeft
            )
        }
        val latest = connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT l.*, a.full_name AS agent_name, a.email AS agent_email
                FROM licenses l
                LEFT JOIN agents a ON a.id = l.agent_id
                WHERE l.agent_id=?
                ORDER BY coalesce(l.activated_at, l.issued_at) DESC
                LIMIT 1
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, agentId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toLicense() else null }
            }
        }
        val msg = when (latest?.status) {
            LicenseStatus.EXPIRED -> "License expired — contact admin to renew"
            LicenseStatus.REVOKED -> "License revoked — contact admin"
            else -> if (trialExpires != null) {
                "Your 3-month free trial has ended. Enter a license key to continue."
            } else {
                "No active license — enter a license key to activate"
            }
        }
        return LicenseStatusResponse(
            licensed = false,
            license = latest,
            message = msg,
            accessGranted = false,
            onTrial = false,
            trialStartedAt = trialStarted,
            trialExpiresAt = trialExpires,
            trialDaysLeft = 0
        )
    }

    fun activateLicense(agentId: String, licenseKey: String): LicenseStatusResponse {
        refreshExpiredLicenses()
        val agent = getAgent(agentId) ?: return LicenseStatusResponse(
            licensed = false,
            message = "Agent not found"
        )
        if (!agent.enabled) {
            return LicenseStatusResponse(licensed = false, message = "Agent account is disabled")
        }
        val existing = getActiveLicenseForAgent(agentId)
        if (existing != null) {
            return LicenseStatusResponse(
                licensed = true,
                license = existing,
                message = "Already licensed until ${formatExpiry(existing.expiresAt)}",
                accessGranted = true
            )
        }
        val key = normalizeLicenseKey(licenseKey)
        if (!key.matches(Regex("^RG-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$"))) {
            return LicenseStatusResponse(licensed = false, message = "Invalid license key format")
        }
        val now = System.currentTimeMillis()
        return connection().use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement("SELECT * FROM licenses WHERE license_key=?").use { ps ->
                    ps.setString(1, key)
                    ps.executeQuery().use { rs ->
                        if (!rs.next()) {
                            conn.rollback()
                            return LicenseStatusResponse(licensed = false, message = "License key not found")
                        }
                        val id = rs.getString("id")
                        val status = rs.getString("status")
                        val boundAgent = rs.getString("agent_id")
                        when {
                            status == LicenseStatus.REVOKED -> {
                                conn.rollback()
                                return LicenseStatusResponse(licensed = false, message = "License has been revoked")
                            }
                            status == LicenseStatus.EXPIRED -> {
                                conn.rollback()
                                return LicenseStatusResponse(licensed = false, message = "License has expired")
                            }
                            status == LicenseStatus.ACTIVE && boundAgent != null && boundAgent != agentId -> {
                                conn.rollback()
                                return LicenseStatusResponse(
                                    licensed = false,
                                    message = "License already used by another agent"
                                )
                            }
                            status == LicenseStatus.ACTIVE && boundAgent == agentId -> {
                                val lic = getLicense(id)!!
                                conn.commit()
                                return LicenseStatusResponse(
                                    licensed = true,
                                    license = lic,
                                    message = "License already active",
                                    accessGranted = true
                                )
                            }
                            status != LicenseStatus.UNUSED && status != LicenseStatus.ACTIVE -> {
                                conn.rollback()
                                return LicenseStatusResponse(licensed = false, message = "License cannot be activated")
                            }
                        }
                        val expiresAt = now + YEAR_MS
                        conn.prepareStatement(
                            """
                            UPDATE licenses
                            SET agent_id=?, activated_at=?, expires_at=?, status=?
                            WHERE id=?
                            """.trimIndent()
                        ).use { upd ->
                            upd.setString(1, agentId)
                            upd.setLong(2, now)
                            upd.setLong(3, expiresAt)
                            upd.setString(4, LicenseStatus.ACTIVE)
                            upd.setString(5, id)
                            upd.executeUpdate()
                        }
                        conn.commit()
                        val lic = getLicense(id)!!
                        LicenseStatusResponse(
                            licensed = true,
                            license = lic,
                            message = "License activated until ${formatExpiry(expiresAt)}",
                            accessGranted = true
                        )
                    }
                }
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun assignLicense(licenseId: String, agentId: String): LicenseStatusResponse {
        if (getAgent(agentId) == null) {
            return LicenseStatusResponse(licensed = false, message = "Agent not found")
        }
        val lic = getLicense(licenseId) ?: return LicenseStatusResponse(
            licensed = false,
            message = "License not found"
        )
        return when (lic.status) {
            LicenseStatus.UNUSED -> activateLicense(agentId, lic.licenseKey)
            LicenseStatus.ACTIVE -> {
                if (lic.agentId == agentId) {
                    LicenseStatusResponse(licensed = true, license = lic, message = "Already assigned")
                } else {
                    LicenseStatusResponse(licensed = false, message = "License already assigned to another agent")
                }
            }
            else -> LicenseStatusResponse(licensed = false, message = "Cannot assign ${lic.status.lowercase()} license")
        }
    }

    fun revokeLicense(id: String): Boolean {
        return connection().use { conn ->
            conn.prepareStatement(
                "UPDATE licenses SET status=? WHERE id=? AND status!=?"
            ).use { ps ->
                ps.setString(1, LicenseStatus.REVOKED)
                ps.setString(2, id)
                ps.setString(3, LicenseStatus.REVOKED)
                ps.executeUpdate() > 0
            }
        }
    }

    fun extendLicense(id: String): LicenseDto? {
        refreshExpiredLicenses()
        val now = System.currentTimeMillis()
        return connection().use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement("SELECT * FROM licenses WHERE id=?").use { ps ->
                    ps.setString(1, id)
                    ps.executeQuery().use { rs ->
                        if (!rs.next()) {
                            conn.rollback()
                            return null
                        }
                        val status = rs.getString("status")
                        if (status == LicenseStatus.REVOKED || status == LicenseStatus.UNUSED) {
                            conn.rollback()
                            return null
                        }
                        val currentExpiry = rs.getObject("expires_at")?.let { (it as Number).toLong() } ?: now
                        val base = maxOf(currentExpiry, now)
                        val newExpiry = base + YEAR_MS
                        conn.prepareStatement(
                            "UPDATE licenses SET expires_at=?, status=? WHERE id=?"
                        ).use { upd ->
                            upd.setLong(1, newExpiry)
                            upd.setString(2, LicenseStatus.ACTIVE)
                            upd.setString(3, id)
                            upd.executeUpdate()
                        }
                        conn.commit()
                        getLicense(id)
                    }
                }
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun normalizeLicenseKey(raw: String): String =
        raw.trim().uppercase().replace(Regex("\\s+"), "")

    private fun formatExpiry(expiresAt: Long?): String {
        if (expiresAt == null) return "n/a"
        val days = ((expiresAt - System.currentTimeMillis()) / TimeUnit.DAYS.toMillis(1)).coerceAtLeast(0)
        val date = java.time.Instant.ofEpochMilli(expiresAt)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
        return "$date ($days days left)"
    }

    fun upsertPolicy(policy: PolicyDto) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO policies(
                  id, agent_id, is_draft, title, customer_name, customer_email, customer_mobile, customer_nic,
                  address_line1, address_line2, address_line3, vehicle_type, vehicle_number,
                  beneficiary_name, beneficiary_nic, beneficiary_relationship,
                  nic_front_path, nic_rear_path, vrc_path, issue_date, expiry_date, status,
                  created_at, updated_at, renewed_at, deleted
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET
                  agent_id=excluded.agent_id,
                  is_draft=excluded.is_draft,
                  title=excluded.title,
                  customer_name=excluded.customer_name,
                  customer_email=excluded.customer_email,
                  customer_mobile=excluded.customer_mobile,
                  customer_nic=excluded.customer_nic,
                  address_line1=excluded.address_line1,
                  address_line2=excluded.address_line2,
                  address_line3=excluded.address_line3,
                  vehicle_type=excluded.vehicle_type,
                  vehicle_number=excluded.vehicle_number,
                  beneficiary_name=excluded.beneficiary_name,
                  beneficiary_nic=excluded.beneficiary_nic,
                  beneficiary_relationship=excluded.beneficiary_relationship,
                  nic_front_path=excluded.nic_front_path,
                  nic_rear_path=excluded.nic_rear_path,
                  vrc_path=excluded.vrc_path,
                  issue_date=excluded.issue_date,
                  expiry_date=excluded.expiry_date,
                  status=excluded.status,
                  created_at=excluded.created_at,
                  updated_at=excluded.updated_at,
                  renewed_at=excluded.renewed_at,
                  deleted=excluded.deleted
                WHERE policies.updated_at <= excluded.updated_at
                """.trimIndent()
            ).use { ps ->
                var i = 1
                ps.setString(i++, policy.id)
                ps.setString(i++, policy.agentId)
                ps.setInt(i++, if (policy.isDraft) 1 else 0)
                ps.setString(i++, policy.title)
                ps.setString(i++, policy.customerName)
                ps.setString(i++, policy.customerEmail)
                ps.setString(i++, policy.customerMobile)
                ps.setString(i++, policy.customerNic)
                ps.setString(i++, policy.addressLine1)
                ps.setString(i++, policy.addressLine2)
                ps.setString(i++, policy.addressLine3)
                ps.setString(i++, policy.vehicleType)
                ps.setString(i++, policy.vehicleNumber)
                ps.setString(i++, policy.beneficiaryName)
                ps.setString(i++, policy.beneficiaryNic)
                ps.setString(i++, policy.beneficiaryRelationship)
                ps.setString(i++, policy.nicFrontPath)
                ps.setString(i++, policy.nicRearPath)
                ps.setString(i++, policy.vrcPath)
                ps.setString(i++, policy.issueDate)
                ps.setString(i++, policy.expiryDate)
                ps.setString(i++, policy.status)
                ps.setLong(i++, policy.createdAt)
                ps.setLong(i++, policy.updatedAt)
                if (policy.renewedAt == null) ps.setObject(i++, null) else ps.setLong(i++, policy.renewedAt)
                ps.setInt(i, if (policy.deleted) 1 else 0)
                ps.executeUpdate()
            }
        }
    }

    fun softDeletePolicy(id: String): Boolean = connection().use { conn ->
        conn.prepareStatement(
            "UPDATE policies SET deleted=1, updated_at=? WHERE id=?"
        ).use { ps ->
            ps.setLong(1, System.currentTimeMillis())
            ps.setString(2, id)
            ps.executeUpdate() > 0
        }
    }

    fun hardDeletePolicy(id: String): Boolean = connection().use { conn ->
        conn.autoCommit = false
        try {
            conn.prepareStatement("DELETE FROM reminder_logs WHERE policy_id=?").use {
                it.setString(1, id); it.executeUpdate()
            }
            val n = conn.prepareStatement("DELETE FROM policies WHERE id=?").use {
                it.setString(1, id); it.executeUpdate()
            }
            conn.commit()
            n > 0
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    fun policiesForAgent(agentId: String, includeDeleted: Boolean = false): List<PolicyDto> =
        connection().use { conn ->
            val sql = if (includeDeleted) {
                "SELECT * FROM policies WHERE agent_id=? ORDER BY updated_at DESC"
            } else {
                "SELECT * FROM policies WHERE agent_id=? AND deleted=0 ORDER BY updated_at DESC"
            }
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, agentId)
                ps.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.toPolicy()) }
                }
            }
        }

    fun deletedPolicyIdsForAgent(agentId: String): List<String> = connection().use { conn ->
        conn.prepareStatement("SELECT id FROM policies WHERE agent_id=? AND deleted=1").use { ps ->
            ps.setString(1, agentId)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString("id")) }
            }
        }
    }

    fun searchPolicies(query: String?): List<PolicyDto> = connection().use { conn ->
        val q = query?.trim().orEmpty()
        if (q.isBlank()) {
            conn.createStatement().executeQuery(
                "SELECT * FROM policies WHERE deleted=0 ORDER BY expiry_date ASC LIMIT 500"
            ).use { rs -> buildList { while (rs.next()) add(rs.toPolicy()) } }
        } else {
            val like = "%${q.lowercase()}%"
            conn.prepareStatement(
                """
                SELECT * FROM policies
                WHERE deleted=0 AND (
                  lower(customer_name) LIKE ? OR lower(customer_nic) LIKE ?
                  OR lower(vehicle_number) LIKE ? OR lower(agent_id) LIKE ?
                  OR lower(customer_mobile) LIKE ?
                )
                ORDER BY expiry_date ASC
                LIMIT 500
                """.trimIndent()
            ).use { ps ->
                repeat(5) { ps.setString(it + 1, like) }
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toPolicy()) } }
            }
        }
    }

    fun getPolicy(id: String): PolicyDto? = connection().use { conn ->
        conn.prepareStatement("SELECT * FROM policies WHERE id=?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toPolicy() else null }
        }
    }

    fun upsertReminderLog(log: ReminderLogDto) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO reminder_logs(id, policy_id, agent_id, channel, message, days_before_expiry, sent_at)
                VALUES(?,?,?,?,?,?,?)
                ON CONFLICT(id) DO NOTHING
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, log.id)
                ps.setString(2, log.policyId)
                ps.setString(3, log.agentId)
                ps.setString(4, log.channel)
                ps.setString(5, log.message)
                if (log.daysBeforeExpiry == null) ps.setObject(6, null) else ps.setInt(6, log.daysBeforeExpiry)
                ps.setLong(7, log.sentAt)
                ps.executeUpdate()
            }
        }
    }

    fun reminderLogsForAgent(agentId: String): List<ReminderLogDto> = connection().use { conn ->
        conn.prepareStatement(
            "SELECT * FROM reminder_logs WHERE agent_id=? ORDER BY sent_at DESC LIMIT 500"
        ).use { ps ->
            ps.setString(1, agentId)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.toReminder()) }
            }
        }
    }

    fun stats(): AdminStats = connection().use { conn ->
        fun count(sql: String): Int =
            conn.createStatement().executeQuery(sql).use { if (it.next()) it.getInt(1) else 0 }

        val today = LocalDate.now()
        val in30 = today.plusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val now = System.currentTimeMillis()

        // Mark expired before counting
        conn.prepareStatement(
            "UPDATE licenses SET status=? WHERE status=? AND expires_at IS NOT NULL AND expires_at < ?"
        ).use { ps ->
            ps.setString(1, LicenseStatus.EXPIRED)
            ps.setString(2, LicenseStatus.ACTIVE)
            ps.setLong(3, now)
            ps.executeUpdate()
        }

        AdminStats(
            totalAgents = count("SELECT COUNT(*) FROM agents"),
            activeAgents = count("SELECT COUNT(*) FROM agents WHERE enabled=1"),
            totalPolicies = count("SELECT COUNT(*) FROM policies WHERE deleted=0"),
            expiringSoon = count(
                """
                SELECT COUNT(*) FROM policies
                WHERE deleted=0 AND is_draft=0
                  AND expiry_date >= '$todayStr' AND expiry_date <= '$in30'
                  AND status != 'RENEWED'
                """.trimIndent()
            ),
            expired = count(
                """
                SELECT COUNT(*) FROM policies
                WHERE deleted=0 AND is_draft=0 AND expiry_date < '$todayStr'
                """.trimIndent()
            ),
            renewed = count(
                """
                SELECT COUNT(*) FROM policies
                WHERE deleted=0 AND (status='RENEWED' OR renewed_at IS NOT NULL)
                """.trimIndent()
            ),
            totalLicenses = count("SELECT COUNT(*) FROM licenses"),
            unusedLicenses = count("SELECT COUNT(*) FROM licenses WHERE status='${LicenseStatus.UNUSED}'"),
            activeLicenses = count("SELECT COUNT(*) FROM licenses WHERE status='${LicenseStatus.ACTIVE}'"),
            expiredLicenses = count("SELECT COUNT(*) FROM licenses WHERE status='${LicenseStatus.EXPIRED}'"),
            revokedLicenses = count("SELECT COUNT(*) FROM licenses WHERE status='${LicenseStatus.REVOKED}'"),
            onTrialAgents = count(
                """
                SELECT COUNT(*) FROM agents a
                WHERE a.trial_expires_at IS NOT NULL AND a.trial_expires_at > $now
                  AND NOT EXISTS (
                    SELECT 1 FROM licenses l
                    WHERE l.agent_id = a.id AND l.status='${LicenseStatus.ACTIVE}'
                      AND l.expires_at IS NOT NULL AND l.expires_at > $now
                  )
                """.trimIndent()
            )
        )
    }

    private fun java.sql.ResultSet.toLicense() = LicenseDto(
        id = getString("id"),
        licenseKey = getString("license_key"),
        agentId = getString("agent_id"),
        agentName = runCatching { getString("agent_name") }.getOrNull(),
        agentEmail = runCatching { getString("agent_email") }.getOrNull(),
        issuedAt = getLong("issued_at"),
        activatedAt = getObject("activated_at")?.let { (it as Number).toLong() },
        expiresAt = getObject("expires_at")?.let { (it as Number).toLong() },
        status = getString("status"),
        notes = getString("notes")
    )

    private fun java.sql.ResultSet.toAgent() = AgentDto(
        id = getString("id"),
        fullName = getString("full_name"),
        email = getString("email"),
        mobile = getString("mobile"),
        passwordHash = getString("password_hash") ?: "",
        enabled = getInt("enabled") == 1,
        createdAt = getLong("created_at"),
        trialStartedAt = getObject("trial_started_at")?.let { (it as Number).toLong() },
        trialExpiresAt = getObject("trial_expires_at")?.let { (it as Number).toLong() }
    )

    private fun java.sql.ResultSet.toPolicy() = PolicyDto(
        id = getString("id"),
        agentId = getString("agent_id"),
        isDraft = getInt("is_draft") == 1,
        title = getString("title") ?: "MR",
        customerName = getString("customer_name") ?: "",
        customerEmail = getString("customer_email") ?: "",
        customerMobile = getString("customer_mobile") ?: "",
        customerNic = getString("customer_nic") ?: "",
        addressLine1 = getString("address_line1") ?: "",
        addressLine2 = getString("address_line2") ?: "",
        addressLine3 = getString("address_line3") ?: "",
        vehicleType = getString("vehicle_type") ?: "MOTOR_BIKE",
        vehicleNumber = getString("vehicle_number") ?: "",
        beneficiaryName = getString("beneficiary_name") ?: "",
        beneficiaryNic = getString("beneficiary_nic") ?: "",
        beneficiaryRelationship = getString("beneficiary_relationship") ?: "SPOUSE",
        nicFrontPath = getString("nic_front_path"),
        nicRearPath = getString("nic_rear_path"),
        vrcPath = getString("vrc_path"),
        issueDate = getString("issue_date") ?: "",
        expiryDate = getString("expiry_date") ?: "",
        status = getString("status") ?: "DRAFT",
        createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at"),
        renewedAt = getObject("renewed_at")?.let { (it as Number).toLong() },
        deleted = getInt("deleted") == 1
    )

    private fun java.sql.ResultSet.toReminder() = ReminderLogDto(
        id = getString("id"),
        policyId = getString("policy_id"),
        agentId = getString("agent_id"),
        channel = getString("channel") ?: "",
        message = getString("message") ?: "",
        daysBeforeExpiry = getObject("days_before_expiry")?.let { (it as Number).toInt() },
        sentAt = getLong("sent_at")
    )

    fun getSetting(key: String): String? = connection().use { conn ->
        conn.prepareStatement("SELECT value FROM app_settings WHERE key=?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getString("value") else null
            }
        }
    }

    fun setSetting(key: String, value: String) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO app_settings(key, value) VALUES(?,?)
                ON CONFLICT(key) DO UPDATE SET value=excluded.value
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, key)
                ps.setString(2, value)
                ps.executeUpdate()
            }
        }
    }

    fun getSmtpConfig(): SmtpConfig {
        val host = getSetting(SettingKeys.SMTP_HOST).orEmpty()
        val port = getSetting(SettingKeys.SMTP_PORT)?.toIntOrNull() ?: 587
        val username = getSetting(SettingKeys.SMTP_USERNAME).orEmpty()
        val password = getSetting(SettingKeys.SMTP_PASSWORD).orEmpty()
        val fromEmail = getSetting(SettingKeys.SMTP_FROM_EMAIL).orEmpty()
        val fromName = getSetting(SettingKeys.SMTP_FROM_NAME)?.ifBlank { "RenewGuard" } ?: "RenewGuard"
        val useTls = getSetting(SettingKeys.SMTP_USE_TLS)?.equals("true", ignoreCase = true) ?: true
        return SmtpConfig(
            host = host,
            port = port,
            username = username,
            password = password,
            fromEmail = fromEmail,
            fromName = fromName,
            useTls = useTls
        )
    }

    fun getSmtpSettingsDto(): SmtpSettingsDto {
        val cfg = getSmtpConfig()
        return SmtpSettingsDto(
            host = cfg.host,
            port = cfg.port,
            username = cfg.username,
            passwordConfigured = cfg.password.isNotBlank(),
            fromEmail = cfg.fromEmail,
            fromName = cfg.fromName,
            useTls = cfg.useTls,
            configured = cfg.isComplete()
        )
    }

    fun updateSmtpSettings(update: SmtpSettingsUpdate): SmtpSettingsDto {
        setSetting(SettingKeys.SMTP_HOST, update.host.trim())
        setSetting(SettingKeys.SMTP_PORT, update.port.coerceIn(1, 65535).toString())
        setSetting(SettingKeys.SMTP_USERNAME, update.username.trim())
        val newPassword = update.password?.trim().orEmpty()
        if (newPassword.isNotEmpty()) {
            setSetting(SettingKeys.SMTP_PASSWORD, newPassword)
        }
        setSetting(SettingKeys.SMTP_FROM_EMAIL, update.fromEmail.trim())
        setSetting(
            SettingKeys.SMTP_FROM_NAME,
            update.fromName.trim().ifBlank { "RenewGuard" }
        )
        setSetting(SettingKeys.SMTP_USE_TLS, if (update.useTls) "true" else "false")
        return getSmtpSettingsDto()
    }

    /** Consistent SQLite snapshot via VACUUM INTO (does not include SMTP password in a separate export). */
    fun vacuumBackupTo(target: File) {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        val path = target.absolutePath.replace("'", "''")
        connection().use { conn ->
            conn.createStatement().execute("VACUUM INTO '$path'")
        }
    }
}

/** Days until expiry helper (unused but handy for future filters). */
fun daysUntil(expiry: String): Long? = try {
    ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(expiry))
} catch (_: Exception) {
    null
}
