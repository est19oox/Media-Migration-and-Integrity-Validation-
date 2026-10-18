#!/usr/bin/env python3
"""Render the repository's aggregate boundaries as a readable PNG."""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parent
OUT = ROOT / "mmiv-aggregates.png"
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

image = Image.new("RGB", (2000, 1850), "#f7f9fc")
draw = ImageDraw.Draw(image)
title_font = ImageFont.truetype(BOLD, 47)
context_font = ImageFont.truetype(BOLD, 31)
root_font = ImageFont.truetype(BOLD, 25)
body_font = ImageFont.truetype(FONT, 22)
note_font = ImageFont.truetype(FONT, 20)


def label(x, y, value, font, color="#183047"):
    draw.text((x, y), value, font=font, fill=color)


def card(x, y, w, h, title, color):
    draw.rounded_rectangle((x, y, x + w, y + h), radius=22,
                           fill="white", outline=color, width=4)
    draw.rounded_rectangle((x, y, x + w, y + 65), radius=20, fill=color)
    draw.rectangle((x, y + 35, x + w, y + 65), fill=color)
    label(x + 24, y + 12, title, context_font, "white")


def aggregate(x, y, w, name, detail, height=91):
    draw.rounded_rectangle((x, y, x + w, y + height), radius=12,
                           fill="#edf4fc", outline="#a9c1da", width=2)
    label(x + 16, y + 9, name, root_font)
    label(x + 16, y + 47, detail, body_font, "#40566b")


def repository(x, y, line1, line2):
    draw.line((x, y - 13, x, y), fill="#8799ad", width=3)
    label(x, y, line1, note_font, "#41576c")
    label(x, y + 29, line2, note_font, "#41576c")


label(54, 26, "MMIV aggregate boundaries", title_font)
label(57, 91, "Each blue box is an aggregate root. Repositories persist roots within their own context.",
      body_font, "#52677c")

# Two columns keep the labels readable at normal browser zoom.
left, right, width = 55, 1025, 920

card(left, 145, width, 455, "USER ACCESS", "#3b9360")
aggregate(left + 25, 225, width - 50, "User", "Owns grants, invitations, resets, role and session version")
aggregate(left + 25, 332, width - 50, "Folder", "Owns folder identity and allowed names")
repository(left + 31, 453, "UserRepository + FolderRepository",
           "Oracle schema: USER_ACCESS")

card(right, 145, width, 745, "METADATA / DISCOVERY", "#3d85b8")
aggregate(right + 25, 225, width - 50, "MetadataPlan", "Owns assigned target, logical path and name reservation")
aggregate(right + 25, 326, width - 50, "VerificationRun", "Owns closed-run state; blocks late allocations")
aggregate(right + 25, 427, width - 50, "MediaMetadata", "Owns discovered media facts and type policy")
aggregate(right + 25, 528, width - 50, "ValidationLog", "Owns immutable validation evidence")
aggregate(right + 25, 629, width - 50, "MigrationFileInventory", "Request-scoped; source filesystem is authoritative")
repository(right + 31, 756, "Plan / Run / FileMetadata / ValidationLog repositories",
           "Oracle schema: DISCOVERY; Inventory has no repository")

card(left, 630, width, 680, "TRANSFER", "#bd7c2a")
aggregate(left + 25, 710, width - 50, "Transfer", "Owns store, validation and cleanup state transitions")
aggregate(left + 25, 811, width - 50, "StoredFile", "Owns storage key and file deletion scope")
aggregate(left + 25, 912, width - 50, "StoredFolder", "Owns recursive logical-folder deletion scope")
draw.rounded_rectangle((left + 25, 1021, left + width - 25, 1131), radius=12,
                       fill="#fff4e3", outline="#e4c596", width=2)
label(left + 41, 1032, "Supporting domain objects", root_font)
label(left + 41, 1074, "TransferAssignment; FolderUploadManifest", body_font)
repository(left + 31, 1177, "Transfer is request-scoped: no Transfer repository",
           "Stored objects use an S3 storage adapter")

card(right, 920, width, 320, "INTEGRITY", "#ba6262")
aggregate(right + 25, 1000, width - 50, "IntegrityValidationLog",
          "Owns checksum comparison and terminal validation status")
repository(right + 31, 1126, "IntegrityValidationLogRepository",
           "Oracle schema: INTEGRITY_SERVICE (shared by both replicas)")

card(left, 1340, width, 450, "REPORTING", "#8061ad")
aggregate(left + 25, 1420, width - 50, "MigrationReportDocument",
          "Owns event application, totals and final report state")
draw.rounded_rectangle((left + 45, 1524, left + width - 45, 1598), radius=12,
                       fill="#f3ecfc", outline="#c8b5e2", width=2)
label(left + 61, 1540, "Child entity: FileReportEntry (changed via report root)", body_font)
aggregate(left + 25, 1610, width - 50, "AuditEventDocument",
          "Owns event identity and applied / received state")
repository(left + 31, 1722, "MigrationReportRepository + AuditEventRepository",
           "MongoDB: reporting")

card(right, 1270, width, 520, "READING THE BOUNDARIES", "#647c95")
for y, text in (
    (1360, "Aggregate root: enforces its own invariants and transitions"),
    (1420, "Child entity: updated through its aggregate root"),
    (1480, "Repository: persists one aggregate type"),
    (1540, "S3 / Oracle / MongoDB: storage owned by that context"),
    (1600, "Gateway, Eureka and RabbitMQ are infrastructure"),
    (1660, "Cross-context REST / events do not merge aggregates"),
):
    label(right + 30, y, text, body_font)

image.save(OUT, optimize=True)
print(OUT)
