package com.insurance.renewal.backend

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class AgentSession(val agentId: String)

/**
 * Prefer Vite production build at [webDist] (`web/dist`) for /app.
 * Falls back to embedded legacy [agentHtml] when dist is missing.
 */
fun Route.agentAppRoutes(
    db: CloudDatabase,
    dataDir: File,
    agentHtml: String,
    webDist: File? = null
) {
    val uploadsRoot = File(dataDir, "uploads").also { it.mkdirs() }
    val dist = webDist?.takeIf { it.isDirectory && File(it, "index.html").isFile }

    if (dist != null) {
        serveAgentSpa(dist)
    } else {
        get("/app") {
            call.respondText(agentHtml, ContentType.Text.Html)
        }
        get("/app/") {
            call.respondText(agentHtml, ContentType.Text.Html)
        }
    }

    route("/api/app") {
        post("/register") {
            val req = call.receive<AgentRegisterRequest>()
            try {
                val agent = db.registerAgent(req.fullName, req.email, req.mobile, req.password)
                call.sessions.set(AgentSession(agent.id))
                call.respond(meResponse(db, agent))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid registration"))
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                if (msg.contains("UNIQUE", ignoreCase = true)) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("Email already registered"))
                } else {
                    throw e
                }
            }
        }

        post("/login") {
            val req = call.receive<AgentAuthRequest>()
            val agent = db.authenticateAgent(req.identifier, req.password)
            if (agent == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid credentials"))
            } else {
                call.sessions.set(AgentSession(agent.id))
                call.respond(meResponse(db, agent))
            }
        }

        post("/logout") {
            call.sessions.clear<AgentSession>()
            call.respond(MessageResponse("Logged out"))
        }

        post("/password-reset-requests") {
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

        get("/me") {
            val agent = requireAgent(call, db) ?: return@get
            call.respond(meResponse(db, agent))
        }

        post("/change-password") {
            val agent = requireAgent(call, db) ?: return@post
            val req = call.receive<AgentChangePasswordRequest>()
            try {
                val updated = db.changeAgentPassword(agent.id, req.currentPassword, req.newPassword)
                call.respond(meResponse(db, updated))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid request"))
            }
        }

        get("/license") {
            val agent = requireAgent(call, db) ?: return@get
            call.respond(db.getLicenseStatusForAgent(agent.id))
        }

        get("/announcements") {
            requireAgent(call, db) ?: return@get
            call.respond(db.listActiveAnnouncements())
        }

        post("/licenses/activate") {
            val agent = requireAgent(call, db) ?: return@post
            val req = call.receive<ActivateLicenseRequest>()
            val result = db.activateLicense(agent.id, req.licenseKey)
            if (result.licensed || result.accessGranted) call.respond(result)
            else call.respond(HttpStatusCode.BadRequest, result)
        }

        get("/policies") {
            val agent = requireAgent(call, db) ?: return@get
            if (!requireAccess(call, db, agent.id)) return@get
            call.respond(db.policiesForAgent(agent.id))
        }

        get("/policies/{id}") {
            val agent = requireAgent(call, db) ?: return@get
            if (!requireAccess(call, db, agent.id)) return@get
            val id = call.parameters["id"]!!
            val policy = db.getPolicy(id)
            if (policy == null || policy.deleted || policy.agentId != agent.id) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Policy not found"))
            } else {
                call.respond(policy)
            }
        }

        put("/policies") {
            val agent = requireAgent(call, db) ?: return@put
            if (!requireAccess(call, db, agent.id)) return@put
            val body = call.receive<PolicyDto>()
            val now = System.currentTimeMillis()
            val existing = db.getPolicy(body.id)
            if (existing != null && existing.agentId != agent.id) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Not your policy"))
                return@put
            }
            val saved = body.copy(
                agentId = agent.id,
                deleted = false,
                createdAt = existing?.createdAt ?: if (body.createdAt == 0L) now else body.createdAt,
                updatedAt = now
            )
            db.upsertPolicy(saved)
            call.respond(saved)
        }

        delete("/policies/{id}") {
            val agent = requireAgent(call, db) ?: return@delete
            if (!requireAccess(call, db, agent.id)) return@delete
            val id = call.parameters["id"]!!
            if (db.softDeletePolicyForAgent(id, agent.id)) {
                call.respond(MessageResponse("Policy deleted"))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Policy not found"))
            }
        }

        post("/policies/{id}/renew") {
            val agent = requireAgent(call, db) ?: return@post
            if (!requireAccess(call, db, agent.id)) return@post
            val id = call.parameters["id"]!!
            val renewed = db.markPolicyRenewed(id, agent.id)
            if (renewed == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Policy not found"))
            } else {
                call.respond(renewed)
            }
        }

        get("/policies/{id}/reminders") {
            val agent = requireAgent(call, db) ?: return@get
            if (!requireAccess(call, db, agent.id)) return@get
            val id = call.parameters["id"]!!
            val policy = db.getPolicy(id)
            if (policy == null || policy.agentId != agent.id) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Policy not found"))
                return@get
            }
            call.respond(db.reminderLogsForPolicy(id, agent.id))
        }

        post("/reminder-logs") {
            val agent = requireAgent(call, db) ?: return@post
            if (!requireAccess(call, db, agent.id)) return@post
            val log = call.receive<ReminderLogDto>()
            val policy = db.getPolicy(log.policyId)
            if (policy == null || policy.agentId != agent.id) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Not your policy"))
                return@post
            }
            val saved = log.copy(
                id = log.id.ifBlank { UUID.randomUUID().toString() },
                agentId = agent.id,
                sentAt = if (log.sentAt == 0L) System.currentTimeMillis() else log.sentAt
            )
            db.upsertReminderLog(saved)
            call.respond(saved)
        }

        get("/reports") {
            val agent = requireAgent(call, db) ?: return@get
            if (!requireAccess(call, db, agent.id)) return@get
            call.respond(db.buildReports(agent.id))
        }

        post("/upload") {
            val agent = requireAgent(call, db) ?: return@post
            if (!requireAccess(call, db, agent.id)) return@post
            val agentDir = File(uploadsRoot, agent.id).also { it.mkdirs() }
            var savedPath: String? = null
            var originalName = "document"
            call.receiveMultipart().forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        originalName = part.originalFileName?.substringAfterLast('/')?.take(120)
                            ?: "document"
                        val safe = originalName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                        val fileName = "${UUID.randomUUID()}_$safe"
                        val dest = File(agentDir, fileName)
                        part.streamProvider().use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        }
                        savedPath = "${agent.id}/$fileName"
                    }
                    else -> Unit
                }
                part.dispose()
            }
            if (savedPath == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("No file uploaded"))
            } else {
                call.respond(
                    UploadResponse(
                        path = savedPath!!,
                        url = "/api/app/files/$savedPath",
                        fileName = originalName
                    )
                )
            }
        }

        get("/files/{agentId}/{fileName}") {
            val agent = requireAgent(call, db) ?: return@get
            val ownerId = call.parameters["agentId"]!!
            val fileName = call.parameters["fileName"]!!
            if (agent.id != ownerId) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Access denied"))
                return@get
            }
            if (fileName.contains("..") || fileName.contains('/') || fileName.contains('\\')) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid path"))
                return@get
            }
            val file = File(File(uploadsRoot, ownerId), fileName)
            if (!file.exists() || !file.isFile) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("File not found"))
                return@get
            }
            val type = when (file.extension.lowercase()) {
                "png" -> ContentType.Image.PNG
                "jpg", "jpeg" -> ContentType.Image.JPEG
                "gif" -> ContentType.Image.GIF
                "webp" -> ContentType("image", "webp")
                "pdf" -> ContentType.Application.Pdf
                else -> ContentType.Application.OctetStream
            }
            call.response.headers.append(HttpHeaders.CacheControl, "private, max-age=3600")
            call.respondBytes(file.readBytes(), type)
        }
    }
}

private fun Route.serveAgentSpa(webDist: File) {
    val index = File(webDist, "index.html")
    val rootCanonical = webDist.canonicalFile

    get("/app") {
        call.respondFile(index)
    }
    get("/app/") {
        call.respondFile(index)
    }
    get("/app/{path...}") {
        val relative = call.parameters.getAll("path").orEmpty().joinToString("/")
        if (relative.isBlank() || relative.contains("..")) {
            call.respondFile(index)
            return@get
        }
        val candidate = File(webDist, relative)
        val canonical = runCatching { candidate.canonicalFile }.getOrNull()
        if (canonical != null &&
            canonical.isFile &&
            canonical.toPath().startsWith(rootCanonical.toPath())
        ) {
            call.respondFile(canonical)
        } else {
            // SPA client routes (e.g. /app/settings)
            call.respondFile(index)
        }
    }
}

private fun meResponse(db: CloudDatabase, agent: AgentDto): AgentMeResponse {
    val status = db.getLicenseStatusForAgent(agent.id)
    return AgentMeResponse(
        agent = agent.copy(passwordHash = ""),
        licensed = status.licensed,
        accessGranted = status.accessGranted,
        onTrial = status.onTrial,
        trialStartedAt = status.trialStartedAt ?: agent.trialStartedAt,
        trialExpiresAt = status.trialExpiresAt ?: agent.trialExpiresAt,
        trialDaysLeft = status.trialDaysLeft,
        license = status.license,
        licenseMessage = status.message
    )
}

/**
 * Cookie session (web) or X-Agent-Id header (React Native / Expo when Set-Cookie
 * is not exposed to JS). Header is only trusted after a successful login/register
 * that returned this agent id — same LAN Phase-1 model as Android /api sync.
 */
private suspend fun requireAgent(call: ApplicationCall, db: CloudDatabase): AgentDto? {
    val sessionAgentId =
        call.sessions.get<AgentSession>()?.agentId
            ?: call.request.headers["X-Agent-Id"]?.trim()?.takeIf { it.isNotEmpty() }
    if (sessionAgentId == null) {
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Login required"))
        return null
    }
    val agent = db.getAgent(sessionAgentId)
    if (agent == null) {
        call.sessions.clear<AgentSession>()
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Session expired"))
        return null
    }
    if (!agent.enabled) {
        call.sessions.clear<AgentSession>()
        call.respond(HttpStatusCode.Forbidden, ErrorResponse("Account disabled by admin"))
        return null
    }
    return agent
}

/** Allow license activation even when trial ended; block other features. */
private suspend fun requireAccess(call: ApplicationCall, db: CloudDatabase, agentId: String): Boolean {
    val status = db.getLicenseStatusForAgent(agentId)
    if (status.accessGranted) return true
    call.respond(
        HttpStatusCode.PaymentRequired,
        ErrorResponse(status.message.ifBlank { "Trial ended — activate a license to continue" })
    )
    return false
}
