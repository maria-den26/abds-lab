.DEFAULT_GOAL := run
COMPOSE = docker compose --env-file .env.local
.PHONY: run rund stop services gen load setup schema teardown test
run:
	$(COMPOSE) up -d --build --wait
rund:
	$(COMPOSE) up --build
stop:
	$(COMPOSE) down
services:
	$(COMPOSE) ps
schema:
	$(COMPOSE) run --rm --no-deps app schema
gen:
	$(COMPOSE) run --build --rm --no-deps app gen
load:
	$(COMPOSE) run --rm --no-deps app load
setup:
	$(MAKE) schema
	$(MAKE) gen
	$(MAKE) load
teardown:
	$(COMPOSE) run --rm --no-deps app teardown
test:
	$(COMPOSE) build app
