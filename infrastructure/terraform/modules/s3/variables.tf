variable "bucket_name" {
  description = "Name of the S3 landing bucket"
  type        = string
}

variable "environment" {
  description = "Deployment environment"
  type        = string
  default     = "dev"
}

variable "create_folder_structure" {
  description = "Create initial S3 prefix structure"
  type        = bool
  default     = true
}

variable "academic_year" {
  description = "Academic year"
  type        = string
  default     = "AY2026-2027"
}

variable "semesters" {
  description = "Semesters to create"
  type        = set(string)

  default = [
    "Semester_1",
    "Semester_2"
  ]
}

variable "courses" {
  description = "Courses to create"
  type        = set(string)

  default = [
    "CS2103",
    "CS2107",
    "CS2109"
  ]
}

variable "content_folders" {
  description = "Course content folders"
  type        = set(string)

  default = [
    "Lectures",
    "Tutorials",
    "Labs",
    "Assignments"
  ]
}