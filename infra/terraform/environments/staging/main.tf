# Staging: the same modules as production with smaller, cheaper settings. Written and validated in
# CI (fmt, validate, static analysis); never applied without an explicit decision (Phase 7).

locals {
  name = "hip-${var.environment}"
}

module "network" {
  source             = "../../modules/network"
  name               = local.name
  availability_zones = var.availability_zones
  single_nat_gateway = var.single_nat_gateway
}

module "data" {
  source                             = "../../modules/data"
  name                               = local.name
  vpc_id                             = module.network.vpc_id
  data_subnet_ids                    = module.network.data_subnet_ids
  database_client_security_group_ids = [module.services.security_group_ids.core_api, module.services.security_group_ids.ai_service]
  redis_client_security_group_ids    = [module.services.security_group_ids.core_api]
  db_multi_az                        = var.db_multi_az
}

module "services" {
  source                  = "../../modules/services"
  name                    = local.name
  vpc_id                  = module.network.vpc_id
  public_subnet_ids       = module.network.public_subnet_ids
  app_subnet_ids          = module.network.app_subnet_ids
  domain_name             = var.domain_name
  certificate_arn         = var.certificate_arn
  oidc_issuer_url         = var.oidc_issuer_url
  identity_admin_base_url = var.identity_admin_base_url
  image_tag               = var.image_tag
  kms_key_arn             = module.data.kms_key_arn
  db_address              = module.data.db_address
  redis_address           = module.data.redis_address
  documents_bucket        = module.data.documents_bucket
  documents_bucket_arn    = module.data.documents_bucket_arn
  queue_arns              = module.data.queue_arns
  queue_names             = module.data.queue_names
  secret_arns             = module.data.secret_arns
  desired_count           = var.desired_count
}
