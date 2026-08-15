package com.insurance.renewal.backend

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AdminSession(val user: String)

@Serializable
data class EnabledBody(val enabled: Boolean)

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("HOST") ?: "0.0.0.0"
    embeddedServer(Netty, port = port, host = host, module = Application::module).start(wait = true)
}

fun Application.module() {
    val dataDir = File(System.getenv("DATA_DIR") ?: "backend/data")
    val db = CloudDatabase(File(dataDir, "renewguard.db"))
    val mailer = SmtpMailer().also { it.configure(db.getSmtpConfig()) }
    val adminUser = System.getenv("ADMIN_USER") ?: "admin"
    val adminPassword = System.getenv("ADMIN_PASSWORD") ?: "0771617150Tt"
    val adminHtml = javaClass.getResourceAsStream("/admin.html")
        ?.bufferedReader()
        ?.readText()
        ?: "<h1>Admin UI missing</h1>"
    val agentHtml = javaClass.getResourceAsStream("/app.html")
        ?.bufferedReader()
        ?.readText()
        ?: "<h1>Agent app missing</h1>"
    val webDist = resolveWebDist()
    val marketingHtml = javaClass.getResourceAsStream("/marketing/index.html")
        ?.bufferedReader()
        ?.readText()
        ?: "<h1>AntSolutions site missing</h1>"
    val marketingCss = javaClass.getResourceAsStream("/marketing/site.css")
        ?.bufferedReader()
        ?.readText()
        ?: "/* missing */"
    val marketingJs = javaClass.getResourceAsStream("/marketing/site.js")
        ?.bufferedReader()
        ?.readText()
        ?: ""

    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        })
    }
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader("X-Agent-Id")
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowCredentials = true
    }
    val secureCookies = System.getenv("SECURE_COOKIES")
        ?.equals("true", ignoreCase = true) == true
    install(Sessions) {
        cookie<AdminSession>("RG_ADMIN") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = secureCookies
            cookie.extensions["SameSite"] = "Lax"
        }
        cookie<AgentSession>("RG_AGENT") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = secureCookies
            cookie.extensions["SameSite"] = "Lax"
        }
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(cause.message ?: "Server error")
            )
        }
    }

    routing {
        get("/") {
            call.respondText(marketingHtml, ContentType.Text.Html)
        }
        get("/site.css") {
            call.respondText(marketingCss, ContentType.Text.CSS)
        }
        get("/site.js") {
            call.respondText(marketingJs, ContentType.Text.JavaScript)
        }

        get("/download/RenewGuard.apk") {
            val bytes = javaClass.getResourceAsStream("/downloads/RenewGuard.apk")?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Android APK is not available yet"))
                return@get
            }
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment
                    .withParameter(ContentDisposition.Parameters.FileName, "RenewGuard.apk")
                    .toString()
            )
            call.respondBytes(
                bytes,
                ContentType.parse("application/vnd.android.package-archive")
            )
        }

        get("/health") {
            call.respond(HealthResponse())
        }

        agentAppRoutes(db, dataDir, agentHtml, webDist)

        post("/api/agents/upsert") {
            val agent = call.receive<AgentDto>()
            db.upsertAgent(agent)
            call.respond(MessageResponse("Agent upserted"))
        }

        post("/api/agents/authenticate") {
            val req = call.receive<AgentAuthRequest>()
            val agent = db.authenticateAgent(req.identifier, req.password)
            if (agent == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid credentials"))
            } else {
                // Return hash so the app can refresh local credentials after an admin reset.
                call.respond(agent)
            }
        }

        post("/api/password-reset-requests") {
            val req = call.receive<PasswordResetRequestBody>()
            try {
                db.createPasswordResetRequest(req.identifier)
                call.respond(
                    MessageResponse(
                        "Reset request submitted. An admin will set a temporary password."
                    )
                )
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid request"))
            }
        }

        get("/api/agents/{agentId}") {
            val id = call.parameters["agentId"]!!
            val agent = db.getAgent(id)
            if (agent == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("Not found"))
            else call.respond(agent.copy(passwordHash = ""))
        }

        post("/api/agents/{agentId}/sync") {
            val agentId = call.parameters["agentId"]!!
            val body = call.receive<SyncPushRequest>()
            body.agent?.let { db.upsertAgent(it.copy(id = agentId)) }
            body.policies.forEach { db.upsertPolicy(it.copy(agentId = agentId, deleted = false)) }
            body.deletedPolicyIds.forEach { db.softDeletePolicy(it) }
            body.reminderLogs.forEach { db.upsertReminderLog(it.copy(agentId = agentId)) }

            val remoteAgent = db.getAgent(agentId)
            if (remoteAgent != null && !remoteAgent.enabled) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    ErrorResponse("Agent account is disabled by admin")
                )
                return@post
            }
            val licenseStatus = db.getLicenseStatusForAgent(agentId)
            call.respond(
                SyncPullResponse(
                    agent = remoteAgent?.copy(passwordHash = ""),
                    policies = db.policiesForAgent(agentId, includeDeleted = false),
                    reminderLogs = db.reminderLogsForAgent(agentId),
                    deletedPolicyIds = db.deletedPolicyIdsForAgent(agentId),
                    license = licenseStatus.license,
                    licensed = licenseStatus.licensed,
                    accessGranted = licenseStatus.accessGranted,
                    onTrial = licenseStatus.onTrial,
                    trialStartedAt = licenseStatus.trialStartedAt,
                    trialExpiresAt = licenseStatus.trialExpiresAt,
                    trialDaysLeft = licenseStatus.trialDaysLeft
                )
            )
        }

        get("/api/agents/{agentId}/policies") {
            val agentId = call.parameters["agentId"]!!
            call.respond(
                SyncPullResponse(
                    policies = db.policiesForAgent(agentId),
                    deletedPolicyIds = db.deletedPolicyIdsForAgent(agentId)
                )
            )
        }

        get("/api/agents/{agentId}/license") {
            val agentId = call.parameters["agentId"]!!
            if (db.getAgent(agentId) == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Agent not found"))
                return@get
            }
            call.respond(db.getLicenseStatusForAgent(agentId))
        }

        post("/api/licenses/activate") {
            val req = call.receive<ActivateLicenseRequest>()
            val result = db.activateLicense(req.agentId, req.licenseKey)
            if (result.licensed) call.respond(result)
            else call.respond(HttpStatusCode.BadRequest, result)
        }

        put("/api/policies") {
            val policy = call.receive<PolicyDto>()
            db.upsertPolicy(policy.copy(deleted = false))
            call.respond(MessageResponse("Policy saved"))
        }

        delete("/api/policies/{id}") {
            val id = call.parameters["id"]!!
            db.softDeletePolicy(id)
            call.respond(MessageResponse("Policy deleted"))
        }

        post("/api/reminder-logs") {
            val log = call.receive<ReminderLogDto>()
            db.upsertReminderLog(log)
            call.respond(MessageResponse("Log saved"))
        }

        post("/api/admin/login") {
            val req = call.receive<AdminLoginRequest>()
            if (req.username == adminUser && req.password == adminPassword) {
                call.sessions.set(AdminSession(req.username))
                call.respond(MessageResponse("Logged in"))
            } else {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid credentials"))
            }
        }

        post("/api/admin/logout") {
            call.sessions.clear<AdminSession>()
            call.respond(MessageResponse("Logged out"))
        }

        get("/api/admin/me") {
            val session = call.sessions.get<AdminSession>()
            if (session == null) call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Not logged in"))
            else call.respond(AdminMeResponse(session.user))
        }

        get("/api/admin/stats") {
            if (!requireAdmin(call)) return@get
            call.respond(db.stats())
        }

        get("/api/admin/agents") {
            if (!requireAdmin(call)) return@get
            val q = call.request.queryParameters["q"]
            val access = call.request.queryParameters["access"]
            call.respond(db.listAgents(q, access))
        }

        patch("/api/admin/agents/{id}") {
            if (!requireAdmin(call)) return@patch
            val id = call.parameters["id"]!!
            val body = call.receive<AdminUpdateAgentRequest>()
            try {
                val updated = db.updateAgentDetails(
                    id = id,
                    fullName = body.fullName,
                    email = body.email,
                    mobile = body.mobile,
                    enabled = body.enabled
                )
                call.respond(
                    MessageResponse(
                        "Agent updated: ${updated.fullName} · ${updated.email} · ${updated.mobile}"
                    )
                )
            } catch (_: NoSuchElementException) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Agent not found"))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid agent details"))
            }
        }

        delete("/api/admin/agents/{id}") {
            if (!requireAdmin(call)) return@delete
            val id = call.parameters["id"]!!
            if (db.deleteAgent(id)) call.respond(MessageResponse("Agent deleted"))
            else call.respond(HttpStatusCode.NotFound, ErrorResponse("Agent not found"))
        }

        get("/api/admin/password-reset-requests") {
            if (!requireAdmin(call)) return@get
            val status = call.request.queryParameters["status"] ?: "PENDING"
            call.respond(db.listPasswordResetRequests(status))
        }

        delete("/api/admin/password-reset-requests/{id}") {
            if (!requireAdmin(call)) return@delete
            val id = call.parameters["id"]!!
            if (db.deletePasswordResetRequest(id)) {
                call.respond(MessageResponse("Password reset request deleted"))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Reset request not found"))
            }
        }

        post("/api/admin/agents/{id}/password") {
            if (!requireAdmin(call)) return@post
            val id = call.parameters["id"]!!
            val agent = db.getAgent(id)
            if (agent == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Agent not found"))
                return@post
            }
            val body = call.receive<SetPasswordRequest>()
            val plaintext = when {
                body.generateTemp || body.password.isBlank() -> PasswordHasher.generateTempPassword()
                else -> body.password.trim()
            }
            if (plaintext.length < 6) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Password must be at least 6 characters"))
                return@post
            }
            if (!db.setAgentPassword(id, plaintext)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Agent not found"))
                return@post
            }
            db.completePasswordResetRequests(id, body.resetRequestId)

            var emailSent = false
            var emailError: String? = null
            if (body.emailToAgent) {
                if (!mailer.isConfigured()) {
                    emailError = "SMTP is not configured. Open Settings to add SMTP details, or share the password offline."
                } else if (agent.email.isBlank()) {
                    emailError = "Agent has no registered email address."
                } else {
                    try {
                        mailer.sendPasswordResetEmail(agent.email, agent.fullName, plaintext)
                        emailSent = true
                    } catch (e: Exception) {
                        emailError = e.message ?: "Failed to send email"
                    }
                }
            }

            val baseMsg =
                "Password updated. Share the temporary password with the agent offline, then ask them to change it in the app."
            val message = when {
                emailSent -> "$baseMsg Email sent to ${agent.email}."
                emailError != null && body.emailToAgent -> "$baseMsg Email not sent: $emailError"
                else -> baseMsg
            }
            call.respond(
                SetPasswordResponse(
                    message = message,
                    temporaryPassword = plaintext,
                    agentId = id,
                    emailSent = emailSent,
                    emailError = emailError
                )
            )
        }

        get("/api/admin/settings") {
            if (!requireAdmin(call)) return@get
            call.respond(db.getSmtpSettingsDto())
        }

        put("/api/admin/settings") {
            if (!requireAdmin(call)) return@put
            val body = call.receive<SmtpSettingsUpdate>()
            if (body.host.isBlank() && body.fromEmail.isBlank() && body.username.isBlank()) {
                // Allow clearing / partial saves; still persist what was sent
            }
            val saved = db.updateSmtpSettings(body)
            mailer.configure(db.getSmtpConfig())
            call.respond(saved)
        }

        post("/api/admin/settings/test-email") {
            if (!requireAdmin(call)) return@post
            if (!mailer.isConfigured()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("SMTP is not configured. Save complete SMTP settings first.")
                )
                return@post
            }
            val body = call.receive<TestEmailRequest>()
            val to = body.to.trim().ifBlank {
                db.getSmtpConfig().fromEmail
            }
            if (to.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Enter a recipient email address"))
                return@post
            }
            try {
                mailer.sendTestEmail(to)
                call.respond(MessageResponse("Test email sent to $to"))
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(e.message ?: "Failed to send test email")
                )
            }
        }

        get("/api/admin/backup") {
            if (!requireAdmin(call)) return@get
            try {
                val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                    .withZone(ZoneOffset.UTC)
                    .format(Instant.now())
                val zipBytes = buildBackupZip(db, dataDir)
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"renewguard-backup-$stamp.zip\""
                )
                call.respondBytes(zipBytes, ContentType.Application.Zip)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse(e.message ?: "Backup failed")
                )
            }
        }

        post("/api/admin/backup/restore") {
            if (!requireAdmin(call)) return@post
            var uploadedZip: File? = null
            try {
                call.receiveMultipart().forEachPart { part ->
                    when (part) {
                        is PartData.FileItem -> {
                            val tmp = File.createTempFile("renewguard-restore-", ".zip")
                            part.streamProvider().use { input ->
                                tmp.outputStream().use { output -> input.copyTo(output) }
                            }
                            uploadedZip?.delete()
                            uploadedZip = tmp
                        }
                        else -> Unit
                    }
                    part.dispose()
                }
                val zip = uploadedZip
                if (zip == null || !zip.isFile || zip.length() == 0L) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Upload a backup ZIP file"))
                    return@post
                }
                val message = restoreFromBackupZip(db, dataDir, zip, mailer)
                call.respond(MessageResponse(message))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid backup"))
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse(e.message ?: "Restore failed")
                )
            } finally {
                uploadedZip?.delete()
            }
        }

        get("/api/admin/policies") {
            if (!requireAdmin(call)) return@get
            val q = call.request.queryParameters["q"]
            call.respond(db.searchPolicies(q))
        }

        delete("/api/admin/policies/{id}") {
            if (!requireAdmin(call)) return@delete
            val id = call.parameters["id"]!!
            if (db.softDeletePolicy(id)) call.respond(MessageResponse("Policy deleted"))
            else call.respond(HttpStatusCode.NotFound, ErrorResponse("Not found"))
        }

        get("/api/admin/licenses") {
            if (!requireAdmin(call)) return@get
            val q = call.request.queryParameters["q"]
            val status = call.request.queryParameters["status"]
            call.respond(db.listLicenses(q, status))
        }

        post("/api/admin/licenses") {
            if (!requireAdmin(call)) return@post
            val req = call.receive<CreateLicensesRequest>()
            val count = req.count.coerceIn(1, 100)
            try {
                val created = db.createLicenses(count, req.notes)
                call.respond(
                    CreateLicensesResponse(
                        created = created,
                        message = "Created ${created.size} license key(s), each valid for 365 days from activation"
                    )
                )
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid request"))
            }
        }

        post("/api/admin/licenses/{id}/revoke") {
            if (!requireAdmin(call)) return@post
            val id = call.parameters["id"]!!
            if (db.revokeLicense(id)) call.respond(MessageResponse("License revoked"))
            else call.respond(HttpStatusCode.NotFound, ErrorResponse("License not found"))
        }

        post("/api/admin/licenses/{id}/extend") {
            if (!requireAdmin(call)) return@post
            val id = call.parameters["id"]!!
            val updated = db.extendLicense(id)
            if (updated != null) call.respond(updated)
            else call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Cannot extend this license (unused or revoked)")
            )
        }

        post("/api/admin/licenses/{id}/assign") {
            if (!requireAdmin(call)) return@post
            val id = call.parameters["id"]!!
            val body = call.receive<AssignLicenseRequest>()
            val result = db.assignLicense(id, body.agentId)
            if (result.licensed) call.respond(result)
            else call.respond(HttpStatusCode.BadRequest, result)
        }

        get("/api/admin/announcements") {
            if (!requireAdmin(call)) return@get
            call.respond(db.listAnnouncements())
        }

        post("/api/admin/announcements") {
            if (!requireAdmin(call)) return@post
            val req = call.receive<CreateAnnouncementRequest>()
            if (req.message.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Message is required"))
                return@post
            }
            val type = if (req.type in listOf(AnnouncementType.INFO, AnnouncementType.WARNING, AnnouncementType.MAINTENANCE)) {
                req.type
            } else {
                AnnouncementType.INFO
            }
            call.respond(db.createAnnouncement(req.message.trim(), type, req.expiresAt))
        }

        post("/api/admin/announcements/{id}/activate") {
            if (!requireAdmin(call)) return@post
            val id = call.parameters["id"]!!
            if (db.setAnnouncementActive(id, true)) call.respond(MessageResponse("Announcement activated"))
            else call.respond(HttpStatusCode.NotFound, ErrorResponse("Not found"))
        }

        post("/api/admin/announcements/{id}/deactivate") {
            if (!requireAdmin(call)) return@post
            val id = call.parameters["id"]!!
            if (db.setAnnouncementActive(id, false)) call.respond(MessageResponse("Announcement deactivated"))
            else call.respond(HttpStatusCode.NotFound, ErrorResponse("Not found"))
        }

        delete("/api/admin/announcements/{id}") {
            if (!requireAdmin(call)) return@delete
            val id = call.parameters["id"]!!
            if (db.deleteAnnouncement(id)) call.respond(MessageResponse("Announcement deleted"))
            else call.respond(HttpStatusCode.NotFound, ErrorResponse("Not found"))
        }

        get("/admin") {
            call.respondText(adminHtml, ContentType.Text.Html)
        }
        get("/admin/") {
            call.respondText(adminHtml, ContentType.Text.Html)
        }
    }
}

@Serializable
data class HealthResponse(val ok: Boolean = true, val service: String = "renewguard-cloud")

@Serializable
data class AdminMeResponse(val user: String)

private suspend fun requireAdmin(call: io.ktor.server.application.ApplicationCall): Boolean {
    if (call.sessions.get<AdminSession>() != null) return true
    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Admin login required"))
    return false
}

/** Zip a SQLite VACUUM snapshot plus the uploads folder (SMTP password remains inside the DB snapshot). */
private fun buildBackupZip(db: CloudDatabase, dataDir: File): ByteArray {
    val tmpDb = File.createTempFile("renewguard-backup-", ".db")
    try {
        db.vacuumBackupTo(tmpDb)
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("renewguard.db"))
            tmpDb.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()

            val uploads = File(dataDir, "uploads")
            if (uploads.isDirectory) {
                uploads.walkTopDown().filter { it.isFile }.forEach { file ->
                    val relative = uploads.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                    zip.putNextEntry(ZipEntry("uploads/$relative"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }

            zip.putNextEntry(ZipEntry("README-BACKUP.txt"))
            val note = """
                RenewGuard backup
                -----------------
                Contents:
                  renewguard.db  — SQLite database snapshot (agents, policies, licenses, settings including SMTP password)
                  uploads/       — agent document uploads (if any)

                Restore (recommended): Admin → Settings → Restore backup — upload this ZIP while signed in.
                The server snapshots the live DB (and uploads if replacing) to *.pre-restore-<timestamp> before overwrite,
                replaces renewguard.db, replaces uploads/ when present in the ZIP, then reloads SMTP settings.

                Manual restore: stop the backend, replace backend/data/renewguard.db (and uploads/ if desired), then restart.
            """.trimIndent()
            zip.write(note.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return baos.toByteArray()
    } finally {
        tmpDb.delete()
    }
}

/**
 * Restore from an admin backup ZIP.
 * - Requires root entry `renewguard.db` (SQLite).
 * - If `uploads/` is present, the live uploads folder is replaced for consistency with the DB.
 * - Live DB (and uploads when replaced) are copied aside as `*.pre-restore-<UTC stamp>` first.
 */
private fun restoreFromBackupZip(
    db: CloudDatabase,
    dataDir: File,
    zipFile: File,
    mailer: SmtpMailer
): String {
    val extractDir = File.createTempFile("rg-restore-extract-", ".dir").also {
        it.delete()
        it.mkdirs()
    }
    try {
        var foundDb = false
        var hasUploads = false
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/').trimStart('/')
                if (name.contains("..")) {
                    throw IllegalArgumentException("Invalid backup ZIP: unsafe path in archive")
                }
                if (!entry.isDirectory) {
                    when {
                        name == "renewguard.db" -> {
                            val out = File(extractDir, "renewguard.db")
                            out.outputStream().use { zis.copyTo(it) }
                            foundDb = true
                        }
                        name.startsWith("uploads/") -> {
                            val rel = name.removePrefix("uploads/")
                            if (rel.isNotBlank()) {
                                hasUploads = true
                                val dest = File(File(extractDir, "uploads"), rel)
                                dest.parentFile?.mkdirs()
                                dest.outputStream().use { zis.copyTo(it) }
                            }
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        if (!foundDb) {
            throw IllegalArgumentException(
                "Invalid backup ZIP: missing renewguard.db at the archive root"
            )
        }
        val newDb = File(extractDir, "renewguard.db")
        if (!newDb.isFile || newDb.length() < 100L) {
            throw IllegalArgumentException("Invalid backup ZIP: renewguard.db is empty or unreadable")
        }
        val header = ByteArray(16)
        newDb.inputStream().use { input ->
            val read = input.read(header)
            if (read < 16 || !header.decodeToString().startsWith("SQLite format 3")) {
                throw IllegalArgumentException(
                    "Invalid backup ZIP: renewguard.db is not a SQLite database"
                )
            }
        }

        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        val liveDb = db.databaseFile
        val preRestoreDb = File(dataDir, "renewguard.db.pre-restore-$stamp")
        var uploadsReplaced = false
        var preRestoreUploadsName: String? = null

        db.withExclusiveAccess {
            dataDir.mkdirs()
            if (liveDb.exists()) {
                liveDb.copyTo(preRestoreDb, overwrite = true)
            }
            db.replaceDatabaseFile(newDb)

            val liveUploads = File(dataDir, "uploads")
            val extractedUploads = File(extractDir, "uploads")
            if (hasUploads && extractedUploads.isDirectory) {
                if (liveUploads.exists()) {
                    val safety = File(dataDir, "uploads.pre-restore-$stamp")
                    if (safety.exists()) safety.deleteRecursively()
                    val moved = liveUploads.renameTo(safety)
                    if (!moved) {
                        liveUploads.copyRecursively(safety, overwrite = true)
                        liveUploads.deleteRecursively()
                    }
                    preRestoreUploadsName = safety.name
                }
                if (liveUploads.exists()) liveUploads.deleteRecursively()
                extractedUploads.copyRecursively(liveUploads, overwrite = true)
                uploadsReplaced = true
            }
        }

        db.afterRestore()
        mailer.configure(db.getSmtpConfig())

        return buildString {
            append("Restore complete. Database replaced from backup.")
            if (liveDb.exists() && preRestoreDb.exists()) {
                append(" Safety snapshot: ${preRestoreDb.name}.")
            }
            if (uploadsReplaced) {
                append(" Uploads folder replaced from backup")
                preRestoreUploadsName?.let { append(" (previous: $it)") }
                append('.')
            } else {
                append(" No uploads/ in backup — existing uploads left unchanged.")
            }
            append(" SMTP settings reloaded from restored database.")
        }
    } finally {
        extractDir.deleteRecursively()
    }
}

/** Vite build output (`npm run build` in web/). Override with WEB_DIST. */
private fun resolveWebDist(): File? {
    val fromEnv = System.getenv("WEB_DIST")?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it) }
    val candidates = listOfNotNull(
        fromEnv,
        File("web/dist"),
        File("../web/dist")
    )
    return candidates.firstOrNull { it.isDirectory && File(it, "index.html").isFile }
}
