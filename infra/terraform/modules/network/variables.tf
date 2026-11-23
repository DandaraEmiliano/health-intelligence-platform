variable "name" {
  description = "Prefix for resource names, e.g. hip-staging."
  type        = string
}

variable "cidr_block" {
  description = "VPC address range."
  type        = string
  default     = "10.40.0.0/16"
}

variable "availability_zones" {
  description = "Two or more zones; every tier gets one subnet per zone."
  type        = list(string)
}

variable "single_nat_gateway" {
  description = "One NAT gateway for all zones (cheaper, not zone-redundant). Use false in production."
  type        = bool
  default     = true
}
