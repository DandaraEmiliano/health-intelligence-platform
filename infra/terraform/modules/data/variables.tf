variable "name" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "data_subnet_ids" {
  type = list(string)
}

variable "database_client_security_group_ids" {
  description = "Security groups allowed to reach PostgreSQL (core-api and ai-service)."
  type        = list(string)
}

variable "redis_client_security_group_ids" {
  description = "Security groups allowed to reach Redis (core-api)."
  type        = list(string)
}

variable "db_instance_class" {
  type    = string
  default = "db.t4g.medium"
}

variable "db_allocated_storage_gb" {
  type    = number
  default = 50
}

variable "db_multi_az" {
  type    = bool
  default = true
}

variable "redis_node_type" {
  type    = string
  default = "cache.t4g.small"
}

variable "backup_retention_days" {
  type    = number
  default = 14
}
