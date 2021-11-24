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

setup:
	$(call check_defined, target)

	$(info Setup container for $(target) environment)
	$(info linking docker-compose-$(target).xml)
	
	@cd $(workdir) && ln -sf docker-compose-$(target).yml docker-compose.yml
