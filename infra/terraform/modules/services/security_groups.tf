# Who may talk to whom: internet -> ALB -> web / bff -> core-api -> ai-service. Nothing else.
# core-api is not behind the load balancer: browsers reach it only through the BFF (ADR 0014).

resource "aws_security_group" "alb" {
  name        = "${var.name}-alb"
  description = "Public HTTPS entry point"
  vpc_id      = var.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
  description       = "Redirected to HTTPS"
}

resource "aws_vpc_security_group_egress_rule" "alb_to_tasks" {
  for_each                     = { web = aws_security_group.web.id, bff = aws_security_group.bff.id }
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = each.value
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}

resource "aws_security_group" "web" {
  name        = "${var.name}-web"
  description = "nginx serving the SPA"
  vpc_id      = var.vpc_id
}

resource "aws_security_group" "bff" {
  name        = "${var.name}-bff"
  description = "Backend-for-Frontend: browser sessions, token relay to core-api"
  vpc_id      = var.vpc_id
}

resource "aws_security_group" "core_api" {
  name        = "${var.name}-core-api"
  description = "core-api tasks"
  vpc_id      = var.vpc_id
}

resource "aws_security_group" "ai_service" {
  name        = "${var.name}-ai-service"
  description = "ai-service tasks: reachable from core-api only (ADR 0001)"
  vpc_id      = var.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "web_from_alb" {
  security_group_id            = aws_security_group.web.id
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}

resource "aws_vpc_security_group_ingress_rule" "bff_from_alb" {
  security_group_id            = aws_security_group.bff.id
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}

resource "aws_vpc_security_group_ingress_rule" "core_from_bff" {
  security_group_id            = aws_security_group.core_api.id
  referenced_security_group_id = aws_security_group.bff.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}

resource "aws_vpc_security_group_ingress_rule" "ai_from_core" {
  security_group_id            = aws_security_group.ai_service.id
  referenced_security_group_id = aws_security_group.core_api.id
  ip_protocol                  = "tcp"
  from_port                    = 8081
  to_port                      = 8081
}

# Tasks need HTTPS out (AWS APIs, identity provider, LLM API), the database, Redis and ai-service.
# Accepted: Fargate pulls images and reaches the LLM API and identity provider over HTTPS; there is
# no fixed destination range. Interface VPC endpoints can narrow it later.
#trivy:ignore:AVD-AWS-0104
resource "aws_vpc_security_group_egress_rule" "https_out" {
  for_each          = { web = aws_security_group.web.id, bff = aws_security_group.bff.id, core = aws_security_group.core_api.id, ai = aws_security_group.ai_service.id }
  security_group_id = each.value
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_vpc_security_group_egress_rule" "to_vpc" {
  for_each          = { bff = aws_security_group.bff.id, core = aws_security_group.core_api.id, ai = aws_security_group.ai_service.id }
  security_group_id = each.value
  cidr_ipv4         = data.aws_vpc.this.cidr_block
  ip_protocol       = "tcp"
  from_port         = 5432
  to_port           = 8081
  description       = "PostgreSQL, Redis, core-api and ai-service inside the VPC"
}

data "aws_vpc" "this" {
  id = var.vpc_id
}
