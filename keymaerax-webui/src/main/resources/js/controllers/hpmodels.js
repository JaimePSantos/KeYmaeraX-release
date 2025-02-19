angular.module('keymaerax.controllers').controller('ModelUploadCtrl',
  function ($scope, $http, $route, $uibModalInstance, $uibModal, $location, Models, sessionService, spinnerService) {
     /** Model data */
     $scope.template = 'ArchiveEntry "New Entry"\n\nProblem\n  /* fill in dL formula here */\nEnd.\nEnd.'

     $scope.model = {
       modelName: undefined,
       content: $scope.template
     };

     $scope.updateModelContentFromFile = function(fileName, fileContent) {
       $scope.model.content = fileContent;
       if (!fileContent || fileContent == '') $scope.model.content = $scope.template;
       if ($scope.numKyxEntries(fileContent) <= 0) {
         $scope.model.modelName = fileName.substring(0, fileName.indexOf('.'));
       }
       $scope.$digest();
     };

     /* Number of archive entries contained in `content`. */
     $scope.numKyxEntries = function(content) {
        // archives contain lemmas, theorems etc., e.g., search for matches: Theorem "...".
        var entryRegex = /(Theorem|Lemma|ArchiveEntry|Exercise)(\s*)\"[^\"]*\"/g;
        return (content && content.match(entryRegex) || []).length;
     };

     /* Number of tactic entries contained in `content`. */
     $scope.numKyxTactics = function(content) {
       var tacticRegex = /Tactic(\s*)\"[^\"]*\"/g;
       return (content && content.match(tacticRegex) || []).length;
     }

     $scope.uploadContent = function(startProof) {
       var url =  "user/" + sessionService.getUser() + "/modelupload/" + encodeURIComponent($scope.model.modelName);
       upload(url, $scope.model.content,
         startProof && $scope.numKyxEntries($scope.model.content) <= 1 && $scope.numKyxTactics($scope.model.content) <= 0);
     }

     $scope.close = function() { $uibModalInstance.close(); };

     $scope.aceLoaded = function(editor) {
       editor.focus();
     }

     $scope.aceChanged = function(e) {
       var content = e[0];
       var editor = e[1];
       if (content.id == 1) {
         // first edit (id==1) inserts the initial template text; move cursor to beginning of comment and select
         editor.moveCursorTo(3, 2);
         editor.getSelection().setSelectionRange(new ace.Range(3, 2, 3, 31), true);
       }
     }

     var upload = function(url, content, startProof) {
       spinnerService.show('caseStudyImportSpinner');
       $http.post(url, content)
         .then(function(response) {
           if (!response.data.success) {
             if (response.data.errorText) {
               showMessage($uibModal, "Error Uploading Model", response.data.errorText, "md")
             } else {
               showMessage($uibModal, "Unknown Error Uploading Model", "An unknown error that did not raise an uncaught exception occurred while trying to insert a model into the database. Perhaps see the server console output for more information.", "md")
             }
           } else { //Successfully uploaded model!
             $scope.close();
             var modelId = response.data.modelId;
             if (startProof) {
               var uri = 'models/users/' + sessionService.getUser() + '/model/' + modelId + '/createProof'
               $http.post(uri, {proofName: '', proofDescription: ''}).
                 success(function(data) { $location.path('proofs/' + data.id); }).
                 error(function(data, status, headers, config) {
                   console.log('Error starting new proof for model ' + modelId)
                 });
             } else {
               //Update the models list -- this should result in the view being updated?
               $http.get("models/users/" + sessionService.getUser() + "/").success(function(data) {
                 Models.setModels(data);
               });
             }
           }
         })
         .catch(function(err) {
           $uibModal.open({
             templateUrl: 'templates/parseError.html',
             controller: 'ParseErrorCtrl',
             size: 'fullscreen',
             resolve: {
               model: function () { return content; },
               error: function () { return err.data; }
           }});
         })
         .finally(function() { spinnerService.hide('caseStudyImportSpinner'); });
     }

     $scope.$watch('models',
        function () { return Models.getModels(); }
     );

     $scope.$emit('routeLoaded', {theview: 'models'});
});

angular.module('keymaerax.controllers').controller('ModelListCtrl', function ($scope, $http, $uibModal, $route,
    $location, FileSaver, Blob, Models, spinnerService, sessionService, firstTime) {
  $scope.models = Models.getModels();
  $scope.userId = sessionService.getUser();
  $scope.intro.firstTime = firstTime;
  $scope.workingDir = [];

  $scope.intro.introOptions = {
    steps: [
    {
        element: '#modelarchiving',
        intro: "Extract all models (with or without) their proofs into a .kyx archive file.",
        position: 'bottom'
    },
    {
        element: '#modelupload',
        intro: "Upload .kyx model files or .kyx archive files.",
        position: 'bottom'
    },
    {
        element: '#modeltutorialimport',
        intro: "Click 'Import' to add tutorials to your models overview.",
        position: 'bottom'
    },
    {
        element: '#modelopen',
        intro: "Inspect model definitions.",
        position: 'bottom'
    },
    {
        element: '#modelactions',
        intro: "Start new proofs, generate monitor conditions, and synthesize test cases here.",
        position: 'bottom'
    }
    ],
    showStepNumbers: false,
    exitOnOverlayClick: true,
    exitOnEsc: true,
    nextLabel: 'Next', // could use HTML in labels
    prevLabel: 'Previous',
    skipLabel: 'Exit',
    doneLabel: 'Done'
  }

  $scope.examples = [];
  $scope.activeTutorialSlide = 0;
  $http.get("examples/user/" + $scope.userId + "/all").then(function(response) {
      $scope.examples = response.data;
  });

  $scope.readModelList = function(folder) {
    if (folder.length > 0) {
      $http.get("models/users/" + $scope.userId + "/" + encodeURIComponent(folder.join("/"))).then(function(response) {
        Models.setModels(response.data);
      });
    } else {
      $http.get("models/users/" + $scope.userId + "/").then(function(response) {
        Models.setModels(response.data);
      });
    }
  }

  $scope.readModelList($scope.workingDir);

  $scope.setWorkingDir = function(folderIdx) {
    if (folderIdx == undefined) $scope.workingDir = [];
    else $scope.workingDir = $scope.workingDir.slice(0, folderIdx);
    $scope.readModelList($scope.workingDir);
  }

  $scope.open = function (modelId) {
    var modalInstance = $uibModal.open({
      templateUrl: 'partials/modeldialog.html',
      controller: 'ModelDialogCtrl',
      size: 'fullscreen',
      resolve: {
        userid: function() { return $scope.userId; },
        modelid: function() { return modelId; },
        proofid: function() { return undefined; },
        mode: function() { return Models.getModel(modelId).isExercise ? 'exercise' : 'edit'; }
      }
    });
  };

  $scope.openFolder = function(folder) {
    $scope.workingDir.push(folder);
    $scope.readModelList($scope.workingDir);
  }

  $scope.openNewModelDialog = function() {
    $uibModal.open({
      templateUrl: 'templates/modeluploaddialog.html',
      controller: 'ModelUploadCtrl',
      size: 'fullscreen'
    });
  };

  $scope.importRepo = function(repoUrl) {
    spinnerService.show('caseStudyImportSpinner');
    var userId = sessionService.getUser();
    $http.post("models/users/" + userId + "/importRepo", repoUrl).success(function(data) {
      $http.get("models/users/" + userId + "/").success(function(data) {
        Models.addModels(data);
        if($location.path() == "/models") {
          $route.reload();
        } else {
          $location.path( "/models" );
        }
      }).finally(function() { spinnerService.hide('caseStudyImportSpinner'); });
    }).error(function(err) {
       $uibModal.open({
         templateUrl: 'templates/modalMessageTemplate.html',
         controller: 'ModalMessageCtrl',
         size: 'lg',
         resolve: {
           title: function() { return "Error importing examples"; },
           message: function() { return err.textStatus; },
           mode: function() { return "ok"; }
         }
       });
    }).finally(function() { spinnerService.hide('caseStudyImportSpinner'); });
  };

  $scope.deleteModel = function(modelId) {
    $http.post("/user/" + sessionService.getUser() + "/model/" + modelId + "/delete").success(function(data) {
      if(data.errorThrown) {
        showCaughtErrorMessage($uibModal, data, "Model Deleter")
      } else {
        console.log("Model " + modelId + " was deleted. Getting a new model list and reloading the route.")
        $scope.readModelList($scope.workingDir);
      }
    })
  };

  $scope.downloadModel = function(modelid) {
    $http.get("user/" + $scope.userId + "/model/" + modelid).then(function(response) {
      var modelName = response.data.name;
      var fileContent = new Blob([response.data.keyFile], { type: 'text/plain;charset=utf-8' });
      FileSaver.saveAs(fileContent, modelName + '.kyx');
    });
  }

  currentDateString = function() {
    var today = new Date();
    var dd = today.getDate();
    var mm = today.getMonth() + 1; //@note January is 0
    var yyyy = today.getFullYear();

    if(dd < 10) dd = '0' + dd
    if(mm < 10) mm='0'+mm
    return mm + dd + yyyy;
  }

  $scope.downloadAllModels = function() {
    spinnerService.show('modelProofExportSpinner');
    $http.get("/models/user/" + $scope.userId + "/downloadAllModels/noProofs").then(function(response) {
      var data = new Blob([response.data.fileContents], { type: 'text/plain;charset=utf-8' });
      FileSaver.saveAs(data, 'models_' + currentDateString() + '.kyx');
    })
    .finally(function() { spinnerService.hide('modelProofExportSpinner'); });
  }

  $scope.downloadModels = function() {
    spinnerService.show('modelProofExportSpinner');
    $http.get("/models/user/" + $scope.userId + "/downloadAllModels/noProofs").then(function(response) {
      var data = new Blob([response.data.fileContents], { type: 'text/plain;charset=utf-8' });
      FileSaver.saveAs(data, 'models_' + currentDateString() + '.kyx');
    })
    .finally(function() { spinnerService.hide('modelProofExportSpinner'); });
  }

  //@note almost duplicate of proofs.js downloadAllProofs
  $scope.downloadAllProofs = function() {
    spinnerService.show('modelProofExportSpinner');
    $http.get("/models/user/" + $scope.userId + "/downloadAllModels/withProofs").then(function(response) {
      var data = new Blob([response.data.fileContents], { type: 'text/plain;charset=utf-8' });
      FileSaver.saveAs(data, 'proofs_'+ currentDateString() +'.kyx');
    })
    .finally(function() { spinnerService.hide('modelProofExportSpinner'); });
  }

  //@note duplicate of proofs.js downloadModelProofs
  $scope.downloadModelProofs = function(modelId) {
    spinnerService.show('modelProofExportSpinner');
    $http.get("/models/user/" + $scope.userId + "/model/" + modelId + "/downloadProofs").then(function(response) {
      var data = new Blob([response.data.fileContents], { type: 'text/plain;charset=utf-8' });
      FileSaver.saveAs(data, modelId + '_' + currentDateString() + '.kyx');
    })
    .finally(function() { spinnerService.hide('modelProofExportSpinner'); });
  }

  $scope.deleteAll = function() {
      var modalInstance = $uibModal.open({
        templateUrl: 'partials/deleteallmodelsdialog.html',
        controller: 'DeleteAllModelsDialogCtrl',
        size: 'sm'
      });

      modalInstance.result.then(function () {
        // modal ok
        spinnerService.show('modelDeleteAllSpinner');
        $http.get("/models/user/" + $scope.userId + "/delete/all").then(function(response) {
           Models.setModels([]);
        })
        .finally(function() { spinnerService.hide('modelDeleteAllSpinner'); });
      }, function () {
        // modal dismissed
      });
    }

  $scope.openTactic = function (modelid) {
      var modalInstance = $uibModal.open({
        templateUrl: 'partials/modeltacticdialog.html',
        controller: 'ModelTacticDialogCtrl',
        size: 'fullscreen',
        resolve: {
          userid: function() { return $scope.userId; },
          modelid: function () { return modelid; }
        }
      });
  };

  $scope.runTactic = function (modelid) {
    $http.post("user/" + $scope.userId + "/model/" + modelid + "/tactic/run").then(function(response) {
        if (respnse.data.errorThrown) showCaughtErrorMessage($uibModal, response.data, "Error While Running Tactic");
        else console.log("Done running tactic");
    });
  }

  $scope.testsynthesis = function(modelid) {
      var modalInstance = $uibModal.open({
        templateUrl: 'templates/testsynthesis.html',
        controller: 'TestSynthCtrl',
        size: 'lg',
        resolve: {
          userid: function() { return $scope.userId; },
          modelid: function() { return modelid; }
        }
      })
    }

  $scope.$watch('models',
      function (newModels) { if (newModels) Models.setModels(newModels); }
  );
  $scope.$emit('routeLoaded', {theview: 'models'});
});

angular.module('keymaerax.controllers').filter("unique", function() {
  return function(items, property) {
    var result = [];
    var propVals = [];
    angular.forEach(items, function(item) {
      var propVal = item[property];
      if (propVals.indexOf(propVal) === -1) {
        propVals.push(propVal);
        result.push(item);
      }
    });
    return result;
  };
});

angular.module('keymaerax.controllers').controller('ModelDialogCtrl',
    function ($scope, $route, $http, $uibModal, $uibModalInstance, $location, Models, userid, modelid, proofid, mode) {
  $scope.mode = mode;
  $scope.proofId = proofid;
  $scope.model = undefined;         // model with edits
  $scope.origModel = undefined;     // original model from database
  $scope.save = {
    cmd: undefined,                 // save command: set on submit buttons
    editable: (mode === 'exercise' || mode === 'proofedit'),
    editor: undefined
  }

  $http.get("user/" + userid + "/model/" + modelid).then(function(response) {
    $scope.model = response.data;
    $scope.model.showModelIllustrations = true;
    $scope.origModel = JSON.parse(JSON.stringify(response.data)); // deep copy
  });

  $scope.aceLoaded = function(editor) {
    editor.setReadOnly(!$scope.save.editable);
    $scope.save.editor = editor;
  }

  $scope.enableEditing = function() {
    $scope.modelDataForm.$show();
    $scope.save.editor.setReadOnly(false);
  }

  /** Deletes all proofs of the model */
  $scope.deleteModelProofSteps = function(onSuccess) {
    $http.post('user/' + userid + "/model/" + modelid + "/deleteProofSteps").success(function(data) {
      if (data.success) {
        $scope.model.numAllProofSteps = 0;
        onSuccess();
      }
    });
  }

  $scope.checkModelData = function() {
    if ($scope.origModel.name !== $scope.model.name || $scope.origModel.title !== $scope.model.title
     || $scope.origModel.description !== $scope.model.description
     || $scope.origModel.keyFile !== $scope.model.keyFile) {
      if ($scope.model.numAllProofSteps > 0) {
        $scope.deleteModelProofSteps($scope.uploadModel);
      } else { $scope.uploadModel(); }
      return false;           // form will not close automatically -> $scope.save.cmd() on successful parsing
    } else {
      $uibModalInstance.close();
      if ($scope.save.cmd) $scope.save.cmd();
      return true;
    }
  }

  $scope.uploadModel = function() {
    var data = {
      name: $scope.model.name,
      title: $scope.model.title,
      description: $scope.model.description,
      content: $scope.model.keyFile
    };
    $http.post("user/" + userid + "/model/" + modelid + "/update", data).then(function(response) {
      var model = Models.getModel(modelid);
      if (model) {
        // model === undefined on proof page reload
        model.name = $scope.model.name;
        model.title = $scope.model.title;
        model.description = $scope.model.description;
        model.keyFile = $scope.model.keyFile;
        $scope.origModel = JSON.parse(JSON.stringify($scope.model)); // deep copy
      }
      $uibModalInstance.close();
      $scope.save.cmd();
    })
    .catch(function(err) {
      $scope.modelDataForm.$setError("", err.data.textStatus);
      $uibModal.open({
        templateUrl: 'templates/parseError.html',
        controller: 'ParseErrorCtrl',
        size: 'fullscreen',
        resolve: {
          model: function () { return $scope.model.keyFile; },
          error: function () { return err.data; }
      }});
    });
  }

  $scope.startProof = function() {
    var uri = 'models/users/' + userid + '/model/' + $scope.model.id + '/createProof'
    $http.post(uri, {proofName: '', proofDescription: ''}).
      success(function(data) { $location.path('proofs/' + data.id); }).
      error(function(data, status, headers, config) {
        console.log('Error starting new proof for model ' + modelid)
      });
  }

  $scope.redoProof = function() {
    $route.reload();
  }

  $scope.modelIsComplete = function() { return $scope.model && $scope.model.keyFile.indexOf('__________') < 0; }

  $scope.checkName = function(name) {
    var nameIsUnique = $.grep(Models.getModels(), function(m) { return m.name === name && m.id !== modelid; }).length == 0;
    if (name === undefined || name === "") return "Name is mandatory. Please enter a name.";
    else if (!nameIsUnique) return "Model with name " + name + " already exists. Please choose a different name."
    else return true;
  }

  $scope.cancel = function() {
    $scope.model.keyFile = $scope.origModel.keyFile;
    $uibModalInstance.close();
  };

  $scope.refreshModels = function() {
    // Update the models list
    $http.get("models/users/" + userid + "/").success(function(data) {
      Models.setModels(data);
    });
  };

  $scope.showModelIllustrations = function(show) {
    $scope.model.showModelIllustrations = show;
    $scope.$apply(); // required since called from standard JavaScript onerror
  }
});

angular.module('keymaerax.controllers').controller('ModelTacticDialogCtrl', function ($scope, $http, $uibModalInstance, userid, modelid) {
  $http.get("user/" + userid + "/model/" + modelid + "/tactic").then(function(response) {
      $scope.modelId = modelid;
      $scope.tactic = response.data;
  });

  $scope.ok = function () { $uibModalInstance.close(); };
});

angular.module('keymaerax.controllers').controller('DeleteAllModelsDialogCtrl', function ($scope, $uibModalInstance) {
  $scope.ok = function () { $uibModalInstance.close(); };
  $scope.cancel = function () { $uibModalInstance.dismiss('cancel'); };
});
