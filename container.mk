ifndef workdir
workdir := .
endif

ignition_cli := $(workdir)/ignition/cli.py

check_defined = $(strip $(foreach 1,$1, \
				$(call __check_defined,$1,$(strip $(value 2)))))
__check_defined = $(if $(value $1),, \
				  $(error Undefined parameter: $1$(if $2, ($2))))

build:
	$(info Build containers.. workdir: $(workdir))
	@cd $(workdir) && docker-compose build

clear_data: down
	$(info Clearing Ignition data..)
	@cd $(workdir) && rm -rf ignition/gateway_data 
setup:
	$(call check_defined, target)
	$(info Setup container for $(target) environment)

start:
	@cd $(workdir) && docker-compose up -d
	@python3 $(ignition_cli) --wait

stop:
	@cd $(workdir) && docker-compose stop

restart:
	@cd $(workdir) && docker-compose restart

down:
	@cd $(workdir) && docker-compose down -v

logs:
	@cd $(workdir) && docker-compose logs -f

backup:
	$(call check_defined, target)
	@python3 $(ignition_cli) --backup $(target)

