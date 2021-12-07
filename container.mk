ifndef workdir
workdir := .
endif

check_defined = $(strip $(foreach 1,$1, \
				$(call __check_defined,$1,$(strip $(value 2)))))
__check_defined = $(if $(value $1),, \
				  $(error Undefined parameter: $1$(if $2, ($2))))

build:
	@echo "build containers"
	@echo "workdir:" $(workdir)
	@cd $(workdir) && docker-compose build

setup:
	$(call check_defined, target)
	$(info Setup container for $(target) environment)

start:
	@cd $(workdir) && docker-compose up -d

stop:
	@cd $(workdir) && docker-compose stop

restart:
	@cd $(workdir) && docker-compose restart

down:
	@cd $(workdir) && docker-compose down -v
