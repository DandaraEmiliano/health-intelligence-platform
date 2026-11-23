variable "name" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "public_subnet_ids" {
  type = list(string)
}

variable "app_subnet_ids" {
  type = list(string)
}

variable "domain_name" {
  description = "Public host name of the web app and API, e.g. app.example.org (one origin, ADR 0014)."
  type        = string
}

variable "certificate_arn" {
  description = "ACM certificate for domain_name, in the same region as the load balancer."
  type        = string
}

variable "oidc_issuer_url" {
  description = "Issuer of the identity provider (Keycloak realm). Hosting Keycloak is outside this module."
  type        = string
}

variable "identity_admin_base_url" {
  description = "Keycloak base URL for the admin API and token endpoint, e.g. https://id.example.org."
  type        = string
}

variable "image_tag" {
  description = "Image tag deployed for every service (the git commit SHA in CD)."
  type        = string
}

variable "kms_key_arn" {
  type = string
}

variable "db_address" {
  type = string
}

variable "redis_address" {
  type = string
}

variable "documents_bucket" {
  type = string
}

variable "documents_bucket_arn" {
  type = string
}

variable "queue_arns" {
  type = map(string)
}

variable "queue_names" {
  type = map(string)
}

variable "secret_arns" {
  type = map(string)
}

variable "desired_count" {
  description = "Tasks per service."
  type        = number
  default     = 2
}

variable "log_retention_days" {
  type    = number
  default = 90
}
