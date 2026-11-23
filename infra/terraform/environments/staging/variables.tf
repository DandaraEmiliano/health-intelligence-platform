variable "region" {
  type    = string
  default = "sa-east-1" # data stays in Brazil
}

variable "environment" {
  type    = string
  default = "staging"
}

variable "availability_zones" {
  type    = list(string)
  default = ["sa-east-1a", "sa-east-1b"]
}

variable "domain_name" {
  type = string
}

variable "certificate_arn" {
  type = string
}

variable "oidc_issuer_url" {
  type = string
}

variable "identity_admin_base_url" {
  type = string
}

variable "image_tag" {
  description = "Set by CD to the commit SHA whose images were pushed."
  type        = string
}

variable "single_nat_gateway" {
  type    = bool
  default = true
}

variable "db_multi_az" {
  type    = bool
  default = false
}

variable "desired_count" {
  type    = number
  default = 1
}
