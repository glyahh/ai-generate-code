package com.dbts.glyahhaigeneratecode.ai;

import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;


public interface aiCodeGeneratorRoutineService {

    /**
     * 使用ai判断用用户的代码生成类型
     * @param Prompt
     * @return
     */
    @SystemMessage(fromResource = "Prompt/Generate_Code_Enum_Routine.txt")
    CodeGenTypeEnum aiCodeGeneratorRoutine (@UserMessage String Prompt);

}
